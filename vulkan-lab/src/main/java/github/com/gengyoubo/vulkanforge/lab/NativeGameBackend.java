package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdWriteTimestamp2;

/** Ordered game draw/resource executor. All incoming objects are CPU data; every GPU object is private Vulkan. */
public final class NativeGameBackend implements AutoCloseable {
    private static final boolean DIAGNOSTICS=Boolean.getBoolean("vulkanforge.nativeDiagnostics"),PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static final int MAX_DRAWS=16384;
    /** Host-visible vertex ring bytes one submission may copy; a frame beyond this is split, never failed. */
    private static final long MAX_SUBMIT_VERTEX_BYTES=256L*1024*1024;
    private final VulkanContext context;
    private final PrintWriter report;
    private final NativeSwapchain presentation;
    private final NativeGeometryCache geometry;
    private static final class DescriptorKey {
        final NativeShaderProgram program;final Texture[] inputs;final int hash;
        DescriptorKey(NativeShaderProgram program,Texture[] inputs){this.program=program;this.inputs=inputs;hash=31*System.identityHashCode(program)+Arrays.hashCode(inputs);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return other instanceof DescriptorKey key&&program==key.program&&Arrays.equals(inputs,key.inputs);}
    }
    private final Map<Integer,NativeShaderProgram> programs=new HashMap<>();
    private static final class Image {
        final GpuImage gpu;final boolean depth;int layout=VK_IMAGE_LAYOUT_UNDEFINED,references;
        /** Stage and access of the last recorded use: the source scope of the next barrier. 0 means unknown. */
        long stage,access;
        Image(GpuImage gpu,boolean depth){this.gpu=gpu;this.depth=depth;}
    }
    private static final long WRITES=VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT|VK_ACCESS_2_TRANSFER_WRITE_BIT|VK_ACCESS_2_SHADER_WRITE_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT;
    /** Image barriers of one step, recorded as a single vkCmdPipelineBarrier2 whose source scopes are the tracked last uses. */
    private static final class Barriers {
        private final VkImageMemoryBarrier2.Buffer buffer;private int count;
        Barriers(MemoryStack stack,int capacity){buffer=VkImageMemoryBarrier2.calloc(Math.max(1,capacity),stack);}
        void add(Image image,int layout,long stage,long access){
            // Read-after-read in the same layout needs no dependency.
            if(image.layout==layout&&layout==VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL&&(image.access&WRITES)==0&&(access&WRITES)==0){image.stage|=stage;image.access|=access;return;}
            boolean fresh=image.layout==VK_IMAGE_LAYOUT_UNDEFINED,known=image.stage!=0;
            buffer.get(count++).sType$Default().image(image.gpu.image).oldLayout(image.layout).newLayout(layout)
                .srcStageMask(fresh?VK_PIPELINE_STAGE_2_NONE:known?image.stage:VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(fresh?0:known?image.access&WRITES:VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT).dstStageMask(stage).dstAccessMask(access)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).subresourceRange(r->r.aspectMask(image.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(image.gpu.levels).layerCount(1));
            image.layout=layout;image.stage=stage;image.access=access;
        }
        void record(VkCommandBuffer cmd,MemoryStack stack){if(count==0)return;vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(buffer.limit(count)));count=0;buffer.clear();}
    }
    private record Texture(Image image,long sampler,long view) {Texture(Image image,long sampler){this(image,sampler,image.gpu.sampledView);}}
    private static final class Target {
        final Image[] attachments,colors;final int[] selection,formats;final Image depth;final int width,height;final boolean privateDepth;
        Target(Image[] attachments,int[] selection,Image depth,int width,int height,boolean privateDepth){
            this.attachments=attachments;this.selection=selection;this.depth=depth;this.width=width;this.height=height;this.privateDepth=privateDepth;
            colors=new Image[selection.length];formats=new int[selection.length];for(int i=0;i<selection.length;i++)if(selection[i]>=0){colors[i]=attachments[selection[i]];if(colors[i]!=null)formats[i]=colors[i].gpu.format;}
        }
        Image color(){return attachments.length==0?null:attachments[0];}Image depth(){return depth;}int width(){return width;}int height(){return height;}
        boolean contains(Image image){if(depth==image)return true;for(Image color:attachments)if(color==image)return true;return false;}
    }
    private final Map<Integer,Texture> textures=new HashMap<>();
    private final Map<Integer,Target> targets=new HashMap<>();
    private final List<Object[]> commands=new ArrayList<>();
    private final long[] samplers=new long[256];
    private long pool,frames,draws,uploads,terrainDraws,entityDraws,embeddiumTerrainDraws,multiDrawPackets,indirectDraws,hoistedInputs,frameSplits,residentReclaims;
    /** Work queued since the last flush, checked against the submission limits as draws arrive. */
    private long queuedDraws,queuedVertexBytes,queuedIndexBytes;
    /**
     * CPU-written state of one frame. With FRAMES slots the CPU records frame N+1 while the GPU still executes frame N;
     * a slot is reused only after its own fence, and resources retired while it records are destroyed then.
     */
    private static final int FRAMES=Math.max(1,Math.min(3,Integer.getInteger("vulkanforge.framesInFlight",2)));
    private static final class Slot {
        VkCommandBuffer command;long fence,descriptors,descriptorUniforms,persistentSamplers;boolean pending,descriptorsStale=true;
        GpuBuffer vertices,indices,uniforms,constantVertices,indirect;
        final Map<DescriptorKey,Long> persistentDescriptors=new HashMap<>();final List<Runnable> retired=new ArrayList<>();final List<String> markers=new ArrayList<>();
    }
    private final Slot[] slots=new Slot[FRAMES];
    private Slot slot;
    private int slotIndex;
    /** The recording slot's command buffer. */
    private VkCommandBuffer command;
    private boolean frameOpen,closed;
    private GpuTimers timers;
    private long vertexBytes,indexBytes,uniformBytes,terrainVertexBytes,terrainIndexBytes,descriptorSets,sceneSubmits,textureBytes;
    private long sceneFenceWaitNs,uploadDrainNs,flushCpuNs,hostCopyNs,descriptorCpuNs,sceneSubmitNs;
    private long descriptorResets,foldedClears;
    private long dynamicMergedDraws,dynamicGpuDraws;
    private long guiMergedDraws,guiGpuDraws;
    private static final boolean GUI_BATCHING=Boolean.getBoolean("vulkanforge.nativeGuiFastPath");
    private static final boolean DYNAMIC_BATCHING=(Boolean.getBoolean("vulkanforge.nativeModSubmission")||Boolean.getBoolean("vulkanforge.nativeDynamicBatching"))&&!Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission");
    private long residentUnreclaimable,splitResidentBudget,splitDrawLimit,splitVertexLimit,splitWaitNs;
    private long hotbarResidentDraws,hotbarResidentBytes;
    /** Labelled GPU timestamps; a section runs from its marker to the next one in the same frame. */
    private static final boolean MARKERS=PROFILE||Boolean.getBoolean("vulkanforge.shaderPackProfile");
    private static final int MAX_MARKERS=256,MARKER_WARMUP=120;
    private long markerPool,markerFrames;
    private int markerBits;
    private double markerPeriod;
    private final Map<String,double[]> markerStats=new TreeMap<>();
    private static long stamp(){return PROFILE?System.nanoTime():0;}
    private static long elapsed(long start){return PROFILE?System.nanoTime()-start:0;}
    public NativeGameBackend(String reportPath,long hwnd,long hinstance,int api,String extensions,boolean validation)throws IOException {
        this(reportPath,hwnd,hinstance,api,extensions,validation,false);
    }
    public static NativeGameBackend offscreen(String reportPath,int api,boolean validation)throws IOException{return new NativeGameBackend(reportPath,0,0,api,"",validation,true);}
    private NativeGameBackend(String reportPath,long hwnd,long hinstance,int api,String extensions,boolean validation,boolean offscreen)throws IOException {
        Path path=Path.of(reportPath).toAbsolutePath();Files.createDirectories(path.getParent());report=new PrintWriter(Files.newBufferedWriter(path),true);
        context=new VulkanContext(api,extensions);NativeSwapchain created=null;long geometryBudget;
        try {
            if(offscreen)context.initialize(0,api,validation);else context.initializeNativeWin32(hwnd,hinstance,validation);created=offscreen?null:new NativeSwapchain(context,report::println,true,FRAMES);
            geometryBudget=geometryBudget(context);
            try(var stack=MemoryStack.stackPush()) {
                var h=stack.mallocLong(1);
                check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,h),"native game command pool");pool=h.get(0);
                var p=stack.mallocPointer(FRAMES);check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES),p),"native game command");
                var sizes=VkDescriptorPoolSize.calloc(2,stack);sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_DRAWS*16);sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(MAX_DRAWS);
                for(int i=0;i<FRAMES;i++){
                    Slot frame=slots[i]=new Slot();frame.command=new VkCommandBuffer(p.get(i),context.device);
                    check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,h),"native game fence");frame.fence=h.get(0);
                    check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_DRAWS).pPoolSizes(sizes),null,h),"native game descriptor pool");frame.descriptors=h.get(0);
                }
                slot=slots[0];command=slot.command;
                for(int i=0;i<samplers.length;i++){int wrap=(i&64)!=0?VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER:(i&2)!=0?VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE:VK_SAMPLER_ADDRESS_MODE_REPEAT;
                    check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().minFilter((i&1)!=0?VK_FILTER_LINEAR:VK_FILTER_NEAREST).magFilter((i&32)!=0?VK_FILTER_LINEAR:VK_FILTER_NEAREST).mipmapMode((i&16)!=0?VK_SAMPLER_MIPMAP_MODE_LINEAR:VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod((i&8)!=0?16:0).compareEnable((i&4)!=0).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL).borderColor((i&128)!=0?VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK:VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                        .addressModeU(wrap).addressModeV(wrap).addressModeW(wrap),null,h),"native game sampler");samplers[i]=h.get(0);}
            }
            report.println("BACKEND="+(offscreen?"VK_NATIVE_OFFSCREEN_TEST":"VK_NATIVE_GAME")+"\nOPENGL_CONTEXTS=0\nSHARED_IMAGES=0\nEXTERNAL_SEMAPHORES=0\nVALIDATION="+(context.validationEnabled()?"ENABLED":"UNAVAILABLE")+"\nFRAMES_IN_FLIGHT="+FRAMES+"\nMULTI_DRAW_INDIRECT="+context.multiDrawIndirect);
            if(PROFILE)timers=new GpuTimers(context,FRAMES,report::println,"NATIVE_SCENE");
        }catch(RuntimeException|Error error){if(created!=null)created.close();context.close();report.close();throw error;}
        presentation=created;geometry=new NativeGeometryCache(context,this::retire,geometryBudget);report.println("RESIDENT_GEOMETRY_BUDGET_MB="+(geometryBudget>>20));
        texture(0,1,1,0,new byte[]{-1,-1,-1,-1});
    }
    /**
     * Resident terrain is all of Embeddium's region arenas plus versions retired by frames still in flight. A quarter of the
     * largest device-local heap, 256 MiB to 2 GiB; {@code -Dvulkanforge.residentGeometryMB} overrides it.
     */
    private static long geometryBudget(VulkanContext context){
        Integer override=Integer.getInteger("vulkanforge.residentGeometryMB");if(override!=null)return Math.max(16L,override)<<20;
        try(var stack=MemoryStack.stackPush()){
            var memory=VkPhysicalDeviceMemoryProperties.malloc(stack);vkGetPhysicalDeviceMemoryProperties(context.physical,memory);long heap=0;
            for(int i=0;i<memory.memoryHeapCount();i++)if((memory.memoryHeaps(i).flags()&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)!=0)heap=Math.max(heap,memory.memoryHeaps(i).size());
            return Math.max(256L<<20,Math.min(2048L<<20,heap/4));
        }
    }
    public void beginFrame() {
        if(closed)throw new IllegalStateException("Native game backend closed");if(frameOpen)return;
        slot=slots[slotIndex];command=slot.command;
        long start=stamp();waitSlot(slotIndex);sceneFenceWaitNs+=elapsed(start);
        if(presentation!=null)presentation.waitSlot(slotIndex);
        // Uploads are ordered before the scene by submission order and their own barriers; only completed batches are recycled here.
        long drainStart=stamp();context.uploads.poll();uploadDrainNs+=elapsed(drainStart);
        for(Runnable action:slot.retired)action.run();slot.retired.clear();frameOpen=true;
    }
    private void waitSlot(int index){
        Slot frame=slots[index];if(!frame.pending)return;
        check(vkWaitForFences(context.device,frame.fence,true,10_000_000_000L),"native game frame complete");frame.pending=false;if(timers!=null)timers.collect(index);collectMarkers(frame,index);
    }
    /** Destroyed once every frame that may reference it has completed: the recording slot's fence covers all earlier submissions. */
    private void retire(Runnable action){slot.retired.add(action);}
    /** Waits for slot {@code index} and destroys what it retired; nothing queued for recording can still reference those. */
    private void reclaim(int index){waitSlot(index);Slot frame=slots[index];for(Runnable action:frame.retired)action.run();frame.retired.clear();}
    /** Submits the work queued so far and waits for it, so the frame continues within the per-submission limits. */
    private void splitFrame(){
        long started=stamp();frameSplits++;flush(null);
        // With no scene commands the latest uploads may not be covered by any scene fence yet.
        context.uploads.drain();
        // The flush may have submitted nothing, so the recording slot's fence alone need not cover the frame still in flight.
        for(int i=0;i<FRAMES;i++)waitSlot(i);
        for(int i=0;i<FRAMES;i++)reclaim(i);
        splitWaitNs+=elapsed(started);
    }
    /**
     * A resident version, or null if the geometry cannot be resident even after reclaiming. Over the budget, versions
     * retired by completed frames are destroyed first, then the frame is split so this frame's retired versions go too.
     */
    private NativeGeometryCache.Buffer resident(int id,long version,byte[] data,long base,int[] ranges){
        NativeGeometryCache.Buffer buffer=geometry.get(id,version,data,base,ranges);if(buffer!=null||data.length==0)return buffer;
        if(!geometry.fitsAfterReclaim(data.length)){residentUnreclaimable++;return null;}
        residentReclaims++;for(int i=0;i<FRAMES;i++)if(i!=slotIndex)reclaim(i);
        buffer=geometry.get(id,version,data,-1,null);if(buffer!=null)return buffer;
        splitResidentBudget++;splitFrame();return geometry.get(id,version,data,-1,null);
    }
    private void endFrame(){frameOpen=false;slotIndex=(slotIndex+1)%FRAMES;}
    public String[] program(int id,String vertex,String fragment,Object[] bindings) {
        return programWithOutputs(id,vertex,fragment,bindings,new Object[0]);
    }
    public String[] programWithOutputs(int id,String vertex,String fragment,Object[] bindings,Object[] outputs) {
        beginFrame();Map<String,Integer> attributes=new LinkedHashMap<>();for(Object binding:bindings){Object[] pair=(Object[])binding;attributes.put((String)pair[0],(Integer)pair[1]);}
        Map<String,Integer> locations=new LinkedHashMap<>();for(Object output:outputs){Object[] pair=(Object[])output;locations.put((String)pair[0],(Integer)pair[1]);}
        NativeShaderProgram next=new NativeShaderProgram(context,vertex,fragment,attributes,locations),old=programs.put(id,next);if(old!=null)retire(old::close);
        return next.samplers.toArray(String[]::new);
    }
    public void deleteProgram(int id){beginFrame();NativeShaderProgram old=programs.remove(id);if(old!=null)retire(old::close);}
    public String[] programUniforms(int id){return Objects.requireNonNull(programs.get(id),"Native shader program").activeUniforms.toArray(String[]::new);}
    /** Validate a fullscreen pack pipeline before publishing a hot-reload candidate. */
    public void prepareShaderPackProgram(int id){
        beginFrame();NativeShaderProgram program=Objects.requireNonNull(programs.get(id),"Shader pack program");
        program.pipeline(new int[]{0,1,0,1,0,32774,32774,0,0,515,0,2305,15,6914,0,4,0},new int[][]{{0,3,5126,0,0,0,20,0},{1,2,5126,0,0,12,20,0}},20);
    }
    public void texture(int id,int width,int height,int flags,byte[] rgba) {
        beginFrame();if(width<=0||height<=0||(long)width*height*4!=rgba.length)throw new IllegalArgumentException("Invalid native texture "+id);
        GpuImage gpu=new GpuImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT,mipLevels(width,height));
        Image image=new Image(gpu,false);
        context.uploads.submit(rgba,(cmd,staging,offset,stack)->{
            transition(cmd,stack,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
            vkCmdCopyBufferToImage(cmd,staging.buffer,gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkBufferImageCopy.calloc(1,stack).bufferOffset(offset).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(width,height,1)));
            generateMips(cmd,stack,image);
        });
        putTexture(id,new Texture(image,samplers[flags&255]));uploads++;if(DIAGNOSTICS)textureBytes+=rgba.length;
    }
    public void textureSampler(int id,int flags){Texture texture=textures.get(id);if(texture!=null)textures.put(id,new Texture(texture.image(),samplers[flags&255],texture.view()));}
    public void textureSwizzle(int id,int[] rgba){
        beginFrame();Texture old=Objects.requireNonNull(textures.get(id));Image image=old.image();
        try(var stack=MemoryStack.stackPush()){
            var info=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.gpu.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(image.gpu.format);
            info.subresourceRange().aspectMask(image.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(image.gpu.levels).layerCount(1);
            info.components().set(component(rgba[0]),component(rgba[1]),component(rgba[2]),component(rgba[3]));var handle=stack.mallocLong(1);
            check(vkCreateImageView(context.device,info,null,handle),"native texture swizzle");textures.put(id,new Texture(image,old.sampler(),handle.get(0)));
            if(old.view()!=image.gpu.sampledView)retireView(old.view());
        }
    }
    private static int component(int value){return switch(value){case 0->VK_COMPONENT_SWIZZLE_ZERO;case 1->VK_COMPONENT_SWIZZLE_ONE;case 6403->VK_COMPONENT_SWIZZLE_R;case 6404->VK_COMPONENT_SWIZZLE_G;case 6405->VK_COMPONENT_SWIZZLE_B;case 6406->VK_COMPONENT_SWIZZLE_A;default->throw new UnsupportedOperationException("Texture component "+value);};}
    private void retain(Image image){if(image!=null)image.references++;}
    private void release(Image image){if(image!=null&&--image.references==0)retire(image.gpu::close);}
    private void putTexture(int id,Texture texture){retain(texture.image());Texture old=textures.put(id,texture);if(old!=null){if(old.view()!=old.image().gpu.sampledView)retireView(old.view());release(old.image());}}
    /** A destroyed view's handle may be reused by a new view, so cached descriptor sets naming it must not be matched again. */
    private void retireView(long view){for(Slot frame:slots)frame.descriptorsStale=true;retire(()->vkDestroyImageView(context.device,view,null));}
    private void release(Target target){for(Image color:target.attachments)release(color);release(target.depth);}
    public void deleteTexture(int id){beginFrame();Texture old=textures.remove(id);if(old!=null){if(old.view()!=old.image().gpu.sampledView)retireView(old.view());release(old.image());}}
    public void renderTexture(int id,int width,int height,int internal,int flags){
        beginFrame();boolean depth=internal==6402||internal==33189||internal==33190||internal==33191||internal==36012;
        int format=depth?VK_FORMAT_D32_SFLOAT:NativeFormats.color(internal),aspect=depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;
        Image image=new Image(new GpuImage(context,width,height,format,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|(depth?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT),aspect,depth?1:mipLevels(width,height)),depth);
        putTexture(id,new Texture(image,samplers[flags&255]));commands.add(new Object[]{3,image});
    }
    public void target(int id,int colorId,int depthId,int width,int height) {
        beginFrame();Target old=targets.get(id);if(old!=null&&old.width()==width&&old.height()==height)return;
        renderTexture(colorId,width,height,32856,0);if(depthId>0)renderTexture(depthId,width,height,36012,0);framebuffer(id,new int[]{colorId},depthId,new int[]{0},width,height);
    }
    public void framebuffer(int id,int[] colors,int depthId,int[] selection,int width,int height){
        beginFrame();if(selection.length>8)throw new IllegalArgumentException("Native framebuffer exceeds eight draw outputs");
        Target old=targets.get(id);Image[] attachments=new Image[colors.length];for(int i=0;i<colors.length;i++)if(colors[i]>0){attachments[i]=Objects.requireNonNull(textures.get(colors[i]),"Framebuffer texture "+colors[i]).image();if(attachments[i].depth||attachments[i].gpu.width<width||attachments[i].gpu.height<height)throw new IllegalArgumentException("Framebuffer color dimensions/format");}
        Image depth=depthId>0?Objects.requireNonNull(textures.get(depthId),"Framebuffer depth").image():old!=null&&old.privateDepth&&old.width==width&&old.height==height?old.depth:new Image(new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_IMAGE_ASPECT_DEPTH_BIT),true);
        for(int slot:selection)if(slot< -1||slot>=attachments.length||slot>=0&&attachments[slot]==null)throw new IllegalArgumentException("Unattached framebuffer output "+slot);
        if(!depth.depth||depth.gpu.width<width||depth.gpu.height<height)throw new IllegalArgumentException("Framebuffer depth dimensions/format");
        if(old!=null&&Arrays.equals(old.attachments,attachments)&&Arrays.equals(old.selection,selection)&&old.depth==depth)return;
        Target next=new Target(attachments,selection.clone(),depth,width,height,depthId<=0);for(Image color:attachments)retain(color);retain(depth);targets.put(id,next);if(old!=null)release(old);
    }
    public void deleteTarget(int id){beginFrame();Target old=targets.remove(id);if(old!=null)release(old);}
    private Target target(int id){Target target=targets.get(id);if(target==null)throw new IllegalStateException("Uninitialized native framebuffer "+id);return target;}
    public void clear(int id,int mask,float[] color,float depth,int[] scissor){beginFrame();commands.add(new Object[]{1,target(id),mask,color.clone(),depth,scissor.clone()});}
    public void blit(int source,int destination,int[] rectangle,int mask,int filter){beginFrame();commands.add(new Object[]{2,target(source),target(destination),rectangle.clone(),mask,filter});}
    public void blitAttachments(int source,int readSlot,int destination,int drawSlot,int[] rectangle,int mask,int filter){beginFrame();commands.add(new Object[]{2,target(source),target(destination),rectangle.clone(),mask,filter,readSlot,drawSlot});}
    public void generateMipmaps(int texture){beginFrame();Image image=Objects.requireNonNull(textures.get(texture),"Mipmap texture").image();if(image.depth)throw new UnsupportedOperationException("Depth mip generation");commands.add(new Object[]{4,image});}
    /** Starts a GPU timing section at this point of the command stream; no-op unless a profile property is set. */
    public void gpuMarker(String label){if(MARKERS){beginFrame();commands.add(new Object[]{7,label});}}
    private void createMarkers(){
        try(var stack=MemoryStack.stackPush()){
            var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physical,properties);var count=stack.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,null);
            var families=VkQueueFamilyProperties.malloc(count.get(0),stack);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,families);
            markerBits=families.get(context.queueFamily).timestampValidBits();markerPeriod=properties.limits().timestampPeriod();if(markerBits==0||markerPeriod<=0)return;
            var handle=stack.mallocLong(1);check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MAX_MARKERS*FRAMES),null,handle),"GPU marker timestamps");markerPool=handle.get(0);
        }
    }
    /** After the frame fence: per-label sums of this frame, one sample per label and frame. */
    private void collectMarkers(Slot frame,int index){
        List<String> recordedMarkers=frame.markers;int n=recordedMarkers.size();if(markerPool==0||n<2){recordedMarkers.clear();return;}
        try(var stack=MemoryStack.stackPush()){
            var values=stack.mallocLong(n);if(vkGetQueryPoolResults(context.device,markerPool,index*MAX_MARKERS,n,values,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT)!=VK_SUCCESS){recordedMarkers.clear();return;}
            if(++markerFrames>MARKER_WARMUP){
                Map<String,Double> sums=new LinkedHashMap<>();for(int i=0;i+1<n;i++)sums.merge(recordedMarkers.get(i),GpuTimers.elapsedTicks(values.get(i),values.get(i+1),markerBits)*markerPeriod/1e6,Double::sum);
                sums.forEach((label,ms)->{double[] stat=markerStats.computeIfAbsent(label,unused->new double[3]);stat[0]++;stat[1]+=ms;stat[2]=Math.max(stat[2],ms);});
            }
        }
        recordedMarkers.clear();
    }
    /** {@code GPU_<label>_COUNT/MEAN_MS/MAX_MS} lines after {@value #MARKER_WARMUP} warm-up frames. */
    public String gpuMarkerReport(){
        StringBuilder text=new StringBuilder("GPU_MARKERS=").append(markerPool!=0?"ENABLED":MARKERS?"UNAVAILABLE":"DISABLED").append('\n');
        markerStats.forEach((label,stat)->text.append(String.format(Locale.ROOT,"GPU_%s_COUNT=%d\nGPU_%s_MEAN_MS=%.4f\nGPU_%s_MAX_MS=%.4f\n",label,(long)stat[0],label,stat[1]/stat[0],label,stat[2])));
        return text.toString();
    }
    public void memoryBarrier(int bits){if(bits!=0){beginFrame();commands.add(new Object[]{6});}}
    public void copyToTexture(int source,int slot,int destination,int x,int y,int width,int height,int destinationX,int destinationY){beginFrame();Image to=Objects.requireNonNull(textures.get(destination),"Copy destination").image();Target from=target(source);Image input=to.depth?from.depth:from.attachments[slot];if(input==null||input==to)throw new IllegalArgumentException("Invalid texture copy source");commands.add(new Object[]{5,input,to,new int[]{x,y,x+width,y+height,destinationX,destinationY,destinationX+width,destinationY+height}});}
    private static int mipLevels(int width,int height){return 32-Integer.numberOfLeadingZeros(Math.max(width,height));}
    /** Packet includes immutable CPU data and optional {vertex id/version,index id/version,index byte offset}. */
    public void draw(int framebuffer,Object[] packet) {
        beginFrame();NativeShaderProgram program=programs.get((Integer)packet[0]);if(program==null)throw new IllegalStateException("Missing native shader program "+packet[0]);
        int[] ids=(int[])packet[11],overrides=packet.length>13?(int[])packet[13]:null;Texture[] inputs=new Texture[ids.length];for(int i=0;i<ids.length;i++){inputs[i]=textures.get(ids[i]);if(inputs[i]==null)throw new IllegalStateException("Missing native texture "+ids[i]);if(overrides!=null&&overrides[i]>=0)inputs[i]=new Texture(inputs[i].image(),samplers[overrides[i]&255],inputs[i].view());}
        NativeGeometryCache.Buffer vertex=null,index=null;
        boolean hotbarResident=packet.length>20&&Boolean.TRUE.equals(packet[20]);
        if((program.terrain||hotbarResident)&&packet.length>12){
            long[] resource=(long[])packet[12];int[][] ranges=packet.length>18?(int[][])packet[18]:null;
            vertex=resident((int)resource[0],resource[1],(byte[])packet[1],resource.length>6?resource[6]:-1,ranges==null?null:ranges[0]);
            index=resident((int)resource[2],resource[3],(byte[])packet[2],resource.length>7?resource[7]:-1,ranges==null?null:ranges[1]);
        }
        if(hotbarResident){hotbarResidentDraws++;hotbarResidentBytes+=(vertex==null?0:((byte[])packet[1]).length)+(index==null?0:indexCopyLength(packet));}
        long vertexCopy=vertex==null?((byte[])packet[1]).length:0,indexCopy=index==null?(indexCopyLength(packet)+3)&~3:0;
        if(vertexCopy>MAX_SUBMIT_VERTEX_BYTES)throw new UnsupportedOperationException("Native draw copies "+vertexCopy+" vertex bytes, above the "+MAX_SUBMIT_VERTEX_BYTES+" byte submission limit");
        if(queuedDraws>0&&(queuedDraws+1>MAX_DRAWS||queuedVertexBytes+vertexCopy>MAX_SUBMIT_VERTEX_BYTES)){if(queuedDraws+1>MAX_DRAWS)splitDrawLimit++;else splitVertexLimit++;splitFrame();}
        Target output=target(framebuffer);
        boolean gui=GUI_BATCHING&&guiPacket(packet)&&program.batchableGui;
        if(vertex==null&&index==null&&(gui||DYNAMIC_BATCHING&&program.batchableDynamic)&&multi(packet)==null&&((int[])packet[7])[15]==4&&(Integer)packet[5]>0&&((byte[])packet[1]).length%(Integer)packet[5]==0&&!commands.isEmpty()){
            Object[] previous=commands.get(commands.size()-1);
            if((Integer)previous[0]==0&&previous[5]==null&&previous[6]==null&&previous[1]==output&&previous[2]==program&&guiPacket((Object[])previous[3])==guiPacket(packet)&&compatibleDynamic((Object[])previous[3],packet)&&Arrays.equals((Texture[])previous[4],inputs)){
                @SuppressWarnings("unchecked") List<Object[]> group=(List<Object[]>)previous[7];if(group==null){group=new ArrayList<>();group.add((Object[])previous[3]);previous[7]=group;}group.add(packet);if(gui)guiMergedDraws++;else dynamicMergedDraws++;
                queuedDraws++;queuedVertexBytes+=vertexCopy;queuedIndexBytes+=indexCopy;return;
            }
        }
        commands.add(new Object[]{0,output,program,packet,inputs,vertex,index,null});queuedDraws++;queuedVertexBytes+=vertexCopy;queuedIndexBytes+=indexCopy;
    }
    private static boolean guiPacket(Object[] packet){return packet.length>19&&Boolean.TRUE.equals(packet[19]);}
    private static boolean compatibleDynamic(Object[] a,Object[] b){
        if(multi(a)!=null||(Integer)a[5]<=0||((byte[])a[1]).length%(Integer)a[5]!=0||!a[5].equals(b[5])||!a[3].equals(b[3]))return false;
        if(!Arrays.equals((int[])a[7],(int[])b[7])||!Arrays.equals((int[])a[8],(int[])b[8])||!Arrays.equals((float[])a[9],(float[])b[9])||!Arrays.deepEquals((Object[])a[10],(Object[])b[10]))return false;
        if(!Arrays.deepEquals(a.length>14?(Object[])a[14]:new Object[0],b.length>14?(Object[])b[14]:new Object[0]))return false;
        int[][] first=(int[][])a[6],second=(int[][])b[6];if(first.length!=second.length)return false;
        for(int i=0;i<first.length;i++)for(int j=0;j<7;j++)if(first[i][j]!=second[i][j])return false;return true;
    }
    @SuppressWarnings("unchecked") private static List<Object[]> dynamicGroup(Object[] entry){return (List<Object[]>)entry[7];}
    private static int dynamicVertices(Object[] entry){List<Object[]> group=dynamicGroup(entry);if(group==null)return ((byte[])((Object[])entry[3])[1]).length;int size=0;for(Object[] p:group)size=Math.addExact(size,((byte[])p[1]).length);return size;}
    private static int dynamicIndexBytes(Object[] entry){int bytes=0;for(Object[] p:dynamicGroup(entry))bytes=Math.addExact(bytes,(indexLength(p)+3)&~3);return bytes;}
    /** Bulk-copy each packet; small indirect records carry the offsets instead of rewriting every index on the CPU. */
    private static void writeDynamic(Object[] entry,ByteBuffer vertices,ByteBuffer indices,IntBuffer indirect){
        int vertexStart=vertices.position(),indexStart=indices.position();
        for(Object[] p:dynamicGroup(entry)){
            int base=Math.addExact((vertices.position()-vertexStart)/(Integer)p[5],p.length>12&&((long[])p[12]).length>5?Math.toIntExact(((long[])p[12])[5]):0);
            indirect.put((Integer)p[4]).put(1).put((indices.position()-indexStart)/((Integer)p[3]==5123?2:4)).put(base).put(0);
            vertices.put((byte[])p[1]);indices.put((byte[])p[2],indexStart(p),indexLength(p));indices.position((indices.position()+3)&~3);
        }
    }
    public void deleteBuffer(int id){beginFrame();geometry.delete(id);}
    private static int indexLength(Object[] packet){return Math.multiplyExact((Integer)packet[4],(Integer)packet[3]==5123?2:4);}
    /** Sub-draw counts of a multi-draw packet, or null. Their offsets are absolute in the packet's index data. */
    private static int[] multi(Object[] packet){return packet.length>15?(int[])packet[15]:null;}
    /** Index bytes a non-resident draw copies: its range, or all index data for a multi-draw. */
    private static int indexCopyLength(Object[] packet){return multi(packet)!=null?((byte[])packet[2]).length:indexLength(packet);}
    private static int indexStart(Object[] packet){return packet.length>12?Math.toIntExact(((long[])packet[12])[4]):0;}
    private GpuBuffer ensure(GpuBuffer buffer,int size,int usage){if(buffer!=null&&buffer.mapped.capacity()>=size)return buffer;if(buffer!=null)buffer.close();return new GpuBuffer(context,Math.max(4096,Integer.highestOneBit(Math.max(1,size-1))<<1),usage);}
    private void flush(Image presented) {
        if(commands.isEmpty()&&(presented==null||presented.layout==VK_IMAGE_LAYOUT_UNDEFINED||presented.layout==VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))return;
        long flushStart=stamp();
        int vb=0,ib=0,count=0,uniformSize=0,samplerInputs=0,subDraws=0;boolean marked=false;for(Object[] entry:commands)if((Integer)entry[0]==0){Object[] p=(Object[])entry[3];if(entry[5]==null)vb=Math.addExact(vb,dynamicVertices(entry));if(entry[6]==null)ib=Math.addExact(ib,dynamicGroup(entry)==null?(indexCopyLength(p)+3)&~3:dynamicIndexBytes(entry));uniformSize=Math.addExact(uniformSize,((NativeShaderProgram)entry[2]).uniformStride);samplerInputs+=((Texture[])entry[4]).length;int[] multi=multi(p);if(multi!=null)subDraws+=multi.length;if(dynamicGroup(entry)!=null)subDraws+=dynamicGroup(entry).size();count++;}else if((Integer)entry[0]==7)marked=true;
        queuedDraws=queuedVertexBytes=queuedIndexBytes=0;
        if(count>MAX_DRAWS||vb>MAX_SUBMIT_VERTEX_BYTES)throw new UnsupportedOperationException("Native frame upload budget exceeded; no draws discarded (draws="+count+", vertexBytes="+vb+", indexBytes="+ib+")");
        Slot frame=slot;GpuBuffer vertices,indices,uniforms,constantVertices,indirect;
        vertices=frame.vertices=ensure(frame.vertices,vb,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);indices=frame.indices=ensure(frame.indices,ib,VK_BUFFER_USAGE_INDEX_BUFFER_BIT);uniforms=frame.uniforms=ensure(frame.uniforms,uniformSize,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        constantVertices=frame.constantVertices=ensure(frame.constantVertices,count*512,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);FloatBuffer constants=constantVertices.mapped.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        indirect=frame.indirect=ensure(frame.indirect,Math.max(20,subDraws*20),VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);IntBuffer indirectData=indirect.mapped.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer vertexData=vertices.mapped.duplicate().clear(),indexData=indices.mapped.duplicate().clear().order(ByteOrder.nativeOrder());FloatBuffer values=uniforms.mapped.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        try(var frameStack=MemoryStack.stackPush()) {
            // Descriptor sets outlive the frame: (program, inputs) keeps its set until a view it names may be destroyed,
            // the uniform ring is reallocated, or this frame could exhaust the pool. The previous frame's fence has completed.
            if(frame.descriptorsStale||frame.descriptorUniforms!=uniforms.buffer||frame.persistentDescriptors.size()+count>MAX_DRAWS||frame.persistentSamplers+samplerInputs>MAX_DRAWS*16){
                check(vkResetDescriptorPool(context.device,frame.descriptors,0),"reset native game descriptors");frame.persistentDescriptors.clear();frame.persistentSamplers=0;frame.descriptorUniforms=uniforms.buffer;frame.descriptorsStale=false;descriptorResets++;
            }
            check(vkResetCommandBuffer(command,0),"reset native game command");
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(frameStack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native game command");
            List<String> recordedMarkers=frame.markers;int markerBase=slotIndex*MAX_MARKERS;
            recordedMarkers.clear();if(marked&&markerPool==0)createMarkers();if(markerPool!=0)vkCmdResetQueryPool(command,markerPool,markerBase,MAX_MARKERS);
            if(timers!=null){timers.begin(command,slotIndex);timers.start(command,slotIndex,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
            Target active=null;int drawIndex=0,uniformOffset=0;long boundPipeline=0,boundIndex=0,boundIndexOffset=-1;int boundIndexType=-1;int[] previousArea=null;float previousBias=Float.NaN,previousSlope=Float.NaN;
            Map<DescriptorKey,Long> descriptorCache=frame.persistentDescriptors;int indirectOffset=0;
            for(int at=0;at<commands.size();at++) {
                Object[] entry=commands.get(at);
                try(var stack=MemoryStack.stackPush()) {
                int kind=(Integer)entry[0];
                if(kind==0) {
                    Target output=(Target)entry[1];NativeShaderProgram program=(NativeShaderProgram)entry[2];Object[] p=(Object[])entry[3];Texture[] inputs=(Texture[])entry[4];
                    boolean transition=false;for(Texture input:inputs){boolean feedback=input.image()==output.depth();for(Image color:output.colors)if(input.image()==color)feedback=true;if(feedback)throw new UnsupportedOperationException("Native framebuffer feedback");if(input.image().layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)transition=true;}
                    if(active!=output||transition){
                        if(active!=null)vkCmdEndRendering(command);
                        // Inputs and attachments share one barrier batch.
                        begin(output,stack,sampled(output,at,stack,output.colors.length+1),0,null,1);active=output;
                    }
                    long copyStart=stamp();
                    byte[] vertexBytes=(byte[])p[1],indexBytes=(byte[])p[2];int vertexOffset=vertexData.position(),indexOffset=indexData.position(),indexLength=indexLength(p);
                    NativeGeometryCache.Buffer residentVertex=(NativeGeometryCache.Buffer)entry[5],residentIndex=(NativeGeometryCache.Buffer)entry[6];
                    List<Object[]> group=dynamicGroup(entry);
                    int dynamicIndirectOffset=indirectData.position()*4;
                    if(group!=null)writeDynamic(entry,vertexData,indexData,indirectData);else if(residentVertex==null)vertexData.put(vertexBytes);
                    int[] multi=multi(p);
                    if(residentIndex==null&&group==null){if(multi==null)indexData.put(indexBytes,indexStart(p),indexLength);else indexData.put(indexBytes);indexData.position((indexData.position()+3)&~3);}
                    program.writeValues((Object[])p[10],values,uniformOffset/4);
                    hostCopyNs+=elapsed(copyStart);
                    if(DIAGNOSTICS){if(residentVertex==null)this.vertexBytes+=dynamicVertices(entry);if(residentIndex==null)this.indexBytes+=group==null?indexLength:dynamicIndexBytes(entry);uniformBytes+=program.uniformBytes;if(program.terrain){if(residentVertex==null)terrainVertexBytes+=vertexBytes.length;if(residentIndex==null)terrainIndexBytes+=indexLength;}}
                    int[] state=(int[])p[7],viewport=(int[])p[8];float[] raster=(float[])p[9];
                    int[][] vertexLayout=program.withConstantInputs((int[][])p[6]);program.writeConstants(vertexLayout,p.length>14?(Object[])p[14]:new Object[0],constants,drawIndex*128);
                    long pipeline=program.pipeline(state,vertexLayout,(Integer)p[5],output.formats,VK_FORMAT_D32_SFLOAT);
                    if(pipeline!=boundPipeline){vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);boundPipeline=pipeline;}
                    program.bindVertexInputs(command,vertexLayout,(Integer)p[5]);
                    if(!Arrays.equals(previousArea,viewport)){
                        vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(viewport[0]).y(viewport[1]).width(viewport[2]).height(viewport[3]).maxDepth(1));
                        vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).offset(o->o.set(viewport[4],viewport[5])).extent(e->e.set(viewport[6],viewport[7])));previousArea=viewport;
                    }
                    if(previousBias!=raster[1]||previousSlope!=raster[0]){vkCmdSetDepthBias(command,raster[1],0,raster[0]);previousBias=raster[1];previousSlope=raster[0];}
                    long descriptorStart=stamp();
                    DescriptorKey key=new DescriptorKey(program,inputs);Long binding=descriptorCache.get(key);
                    if(binding==null){
                    var descriptor=stack.mallocLong(1);check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(frame.descriptors).pSetLayouts(stack.longs(program.textureLayout)),descriptor),"native draw descriptors");
                    var writes=VkWriteDescriptorSet.calloc(inputs.length+1,stack);
                    for(int i=0;i<inputs.length;i++){Texture input=inputs[i];writes.get(i).sType$Default().dstSet(descriptor.get(0)).dstBinding(program.samplerBindings[i]).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(input.sampler()).imageView(input.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));}
                    writes.get(inputs.length).sType$Default().dstSet(descriptor.get(0)).dstBinding(program.uniformBinding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.buffer).range(program.uniformBytes));vkUpdateDescriptorSets(context.device,writes,null);
                    binding=descriptor.get(0);descriptorCache.put(key,binding);frame.persistentSamplers+=inputs.length;descriptorSets++;
                    }
                    descriptorCpuNs+=elapsed(descriptorStart);
                    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,program.layout,0,stack.longs(binding),stack.ints(uniformOffset));
                    vkCmdBindVertexBuffers(command,0,stack.longs(residentVertex==null?vertices.buffer:residentVertex.gpu.buffer),stack.longs(residentVertex==null?vertexOffset:0));
                    if(vertexLayout!=(int[][])p[6])vkCmdBindVertexBuffers(command,1,stack.longs(constantVertices.buffer),stack.longs(drawIndex*512L));
                    long indexBuffer=residentIndex==null?indices.buffer:residentIndex.gpu.buffer,indexByteOffset=residentIndex==null?indexOffset:multi!=null?0:indexStart(p);int indexType=(Integer)p[3]==5123?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32;
                    if(boundIndex!=indexBuffer||boundIndexOffset!=indexByteOffset||boundIndexType!=indexType){vkCmdBindIndexBuffer(command,indexBuffer,indexByteOffset,indexType);boundIndex=indexBuffer;boundIndexOffset=indexByteOffset;boundIndexType=indexType;}
                    int baseVertex=p.length>12&&((long[])p[12]).length>5?Math.toIntExact(((long[])p[12])[5]):0;
                    int drawn=1;
                    if(group!=null){drawn=group.size();int gpuDraws;if(context.multiDrawIndirect){vkCmdDrawIndexedIndirect(command,indirect.buffer,dynamicIndirectOffset,drawn,20);gpuDraws=1;}else{int first=dynamicIndirectOffset/4;for(int i=0;i<drawn;i++)vkCmdDrawIndexed(command,indirectData.get(first+i*5),1,indirectData.get(first+i*5+2),indirectData.get(first+i*5+3),0);gpuDraws=drawn;}if(guiPacket(p))guiGpuDraws+=gpuDraws;else dynamicGpuDraws+=gpuDraws;}
                    else if(multi==null){vkCmdDrawIndexed(command,(Integer)p[4],1,0,baseVertex,0);if(guiPacket(p))guiGpuDraws++;else if(program.batchableDynamic)dynamicGpuDraws++;}
                    else{
                        // One packet per Embeddium region: shared state was bound once; the sections become one indirect draw.
                        int[] offsets=(int[])p[16],bases=(int[])p[17];int indexSize=indexType==VK_INDEX_TYPE_UINT16?2:4;drawn=multi.length;multiDrawPackets++;
                        if(context.multiDrawIndirect){
                            for(int i=0;i<multi.length;i++)indirectData.put(multi[i]).put(1).put(offsets[i]/indexSize).put(bases[i]).put(0);
                            vkCmdDrawIndexedIndirect(command,indirect.buffer,indirectOffset,multi.length,20);indirectOffset+=multi.length*20;indirectDraws++;
                        }else for(int i=0;i<multi.length;i++)vkCmdDrawIndexed(command,multi[i],1,offsets[i]/indexSize,bases[i],0);
                    }
                    indirectOffset=indirectData.position()*4;
                    draws+=drawn;if(program.terrain)terrainDraws+=drawn;if(program.embeddium)embeddiumTerrainDraws+=drawn;if(program.entity)entityDraws+=drawn;drawIndex++;uniformOffset+=program.uniformStride;
                } else if(kind==1) {
                    active=clearCommand(entry,active,at,stack);
                } else if(kind==7) {
                    // Timestamps are legal inside rendering: markers never split a pass.
                    if(markerPool!=0&&recordedMarkers.size()<MAX_MARKERS){vkCmdWriteTimestamp2(command,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,markerPool,markerBase+recordedMarkers.size());recordedMarkers.add((String)entry[1]);}
                } else {
                    if(active!=null){vkCmdEndRendering(command);active=null;}
                    if(kind==2)blitCommand(entry,stack);
                    else if(kind==3)clearImage((Image)entry[1],stack);
                    else if(kind==4)generateMips(command,stack,(Image)entry[1]);
                    else if(kind==5)blitImages((Image)entry[1],(Image)entry[2],(int[])entry[3],VK_FILTER_NEAREST,stack);
                    else if(kind==6)vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT)));
                    else throw new IllegalStateException("Unknown native command "+kind);
                }
                }
            }
            if(active!=null)vkCmdEndRendering(command);
            // The presentation pass consumes the final color in COLOR_ATTACHMENT_OPTIMAL. Every other image keeps its layout;
            // its next use transitions it from the tracked state.
            if(presented!=null&&presented.layout!=VK_IMAGE_LAYOUT_UNDEFINED)transition(command,frameStack,presented,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            if(timers!=null)timers.end(command,slotIndex,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command),"end native game command");context.uploads.flush();check(vkResetFences(context.device,frame.fence),"reset native game fence");
            long submitStart=stamp();check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,frameStack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,frameStack).sType$Default().commandBuffer(command)),frame.fence),"native game submit");sceneSubmitNs+=elapsed(submitStart);sceneSubmits++;frame.pending=true;if(timers!=null)timers.submitted(slotIndex);
        }
        commands.clear();
        flushCpuNs+=elapsed(flushStart);
    }
    private void begin(Target target,MemoryStack stack){begin(target,stack,new Barriers(stack,target.colors.length+1),0,null,1);}
    /**
     * Barriers for a rendering of {@code target} that starts at command {@code from}: every texture sampled by the run of
     * commands that stays inside it (its draws, clears and markers) moves to SHADER_READ up front. Inside the run only the
     * target's attachments are written, so this is the same dependency, and a later first-use input no longer splits the pass.
     */
    private Barriers sampled(Target target,int from,MemoryStack stack,int extra){
        List<Image> inputs=new ArrayList<>();
        for(int at=from;at<commands.size();at++){
            Object[] entry=commands.get(at);int kind=(Integer)entry[0];
            if(kind==7)continue;
            if(kind==1&&entry[1]==target)continue;
            if(kind!=0||entry[1]!=target)break;
            for(Texture input:(Texture[])entry[4]){Image image=input.image();if(!target.contains(image)&&image.layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL&&!inputs.contains(image))inputs.add(image);}
        }
        Barriers barriers=new Barriers(stack,inputs.size()+extra);
        for(Image image:inputs)barriers.add(image,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if(inputs.size()>1)hoistedInputs+=inputs.size()-1;
        return barriers;
    }
    /**
     * Starts dynamic rendering after one batched barrier for {@code barriers} plus the attachments. Attachments in
     * {@code clearMask} use loadOp CLEAR, so a full-target glClear costs neither a load nor a separate clear pass.
     */
    private void begin(Target target,MemoryStack stack,Barriers barriers,int clearMask,float[] clearColor,float clearDepth) {
        boolean depthNew=target.depth().layout==VK_IMAGE_LAYOUT_UNDEFINED,clearColors=(clearMask&16384)!=0,clearDepthBits=(clearMask&256)!=0;
        var color=VkRenderingAttachmentInfo.calloc(target.colors.length,stack);
        for(int i=0;i<target.colors.length;i++){
            Image image=target.colors[i];boolean fresh=image!=null&&image.layout==VK_IMAGE_LAYOUT_UNDEFINED,clear=image!=null&&clearColors;
            if(image!=null)barriers.add(image,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            color.get(i).sType$Default().imageView(image==null?0:image.gpu.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(image==null?VK_ATTACHMENT_LOAD_OP_DONT_CARE:clear||fresh?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(image==null?VK_ATTACHMENT_STORE_OP_DONT_CARE:VK_ATTACHMENT_STORE_OP_STORE);
            if(clear)for(int c=0;c<4;c++)color.get(i).clearValue().color().float32(c,clearColor[c]);
        }
        barriers.add(target.depth(),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        barriers.record(command,stack);
        var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(target.depth().gpu.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(depthNew||clearDepthBits?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);depth.clearValue().depthStencil().depth(clearDepthBits?clearDepth:1);
        vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth).renderArea(r->r.extent(e->e.set(target.width(),target.height()))));
    }
    /**
     * glClear. Inside the target's open rendering it is a vkCmdClearAttachments; a full-target clear otherwise starts
     * the target's rendering with loadOp CLEAR. Rendering is left open, so following draws to the target continue in it.
     */
    private Target clearCommand(Object[] entry,Target active,int index,MemoryStack stack){
        Target target=(Target)entry[1];int mask=(Integer)entry[2];int[] rect=(int[])entry[5];float[] value=(float[])entry[3];
        if(rect[2]<=0||rect[3]<=0)return active;
        boolean full=rect[0]<=0&&rect[1]<=0&&rect[0]+rect[2]>=target.width()&&rect[1]+rect[3]>=target.height();
        if(active!=target){
            if(active!=null)vkCmdEndRendering(command);
            Barriers barriers=sampled(target,index+1,stack,target.colors.length+1);
            if(full){begin(target,stack,barriers,mask,value,(Float)entry[4]);foldedClears++;return target;}
            begin(target,stack,barriers,0,null,1);
        }
        int count=0;if((mask&16384)!=0)for(Image color:target.colors)if(color!=null)count++;if((mask&256)!=0)count++;
        if(count==0)return target;
        var clears=VkClearAttachment.calloc(count,stack);int at=0;
        if((mask&16384)!=0)for(int slot=0;slot<target.colors.length;slot++)if(target.colors[slot]!=null){var clear=clears.get(at++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(slot);for(int c=0;c<4;c++)clear.clearValue().color().float32(c,value[c]);}
        if((mask&256)!=0)clears.get(at).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).clearValue().depthStencil().depth((Float)entry[4]);
        var area=VkClearRect.calloc(1,stack).layerCount(1);area.rect().offset().set(rect[0],rect[1]);area.rect().extent().set(rect[2],rect[3]);vkCmdClearAttachments(command,clears,area);
        return target;
    }
    private void blitCommand(Object[] entry,MemoryStack stack){
        Target from=(Target)entry[1],to=(Target)entry[2];int[] rect=(int[])entry[3];int mask=(Integer)entry[4];
        for(boolean depth:new boolean[]{false,true})if((mask&(depth?256:16384))!=0){
            Image src=depth?from.depth():from.attachments[entry.length>6?(Integer)entry[6]:0],dst=depth?to.depth():to.attachments[entry.length>7?(Integer)entry[7]:0];if(src==null||dst==null)throw new IllegalStateException("Blit attachment missing");
            blitImages(src,dst,rect,!depth&&(Integer)entry[5]==9729?VK_FILTER_LINEAR:VK_FILTER_NEAREST,stack);
        }
    }
    private void blitImages(Image src,Image dst,int[] rect,int filter,MemoryStack stack){
        transition(command,stack,src,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_BLIT_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);transition(command,stack,dst,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_BLIT_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
        var copy=VkImageBlit.calloc(1,stack);int aspect=src.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;copy.srcSubresource().aspectMask(aspect).layerCount(1);copy.dstSubresource().aspectMask(aspect).layerCount(1);copy.srcOffsets(0).set(rect[0],rect[1],0);copy.srcOffsets(1).set(rect[2],rect[3],1);copy.dstOffsets(0).set(rect[4],rect[5],0);copy.dstOffsets(1).set(rect[6],rect[7],1);
        vkCmdBlitImage(command,src.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,dst.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy,filter);
    }
    private void clearImage(Image image,MemoryStack stack){
        transition(command,stack,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_CLEAR_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
        var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(image.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(image.gpu.levels).layerCount(1);
        if(image.depth)vkCmdClearDepthStencilImage(command,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkClearDepthStencilValue.calloc(stack).depth(1),range);
        else vkCmdClearColorImage(command,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkClearColorValue.calloc(stack),range);
    }
    private static void transition(VkCommandBuffer cmd,MemoryStack stack,Image image,int layout,long stage,long access) {
        Barriers barriers=new Barriers(stack,1);barriers.add(image,layout,stage,access);barriers.record(cmd,stack);
    }
    private static void mipToSource(VkCommandBuffer cmd,MemoryStack stack,Image image,int level){
        var barrier=VkImageMemoryBarrier2.calloc(1,stack).sType$Default().image(image.gpu.image).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_BLIT_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).subresourceRange(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(level).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
    }
    private static void generateMips(VkCommandBuffer cmd,MemoryStack stack,Image image){
        transition(cmd,stack,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_BLIT_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
        for(int level=1;level<image.gpu.levels;level++){
            mipToSource(cmd,stack,image,level-1);
            var region=VkImageBlit.calloc(1,stack);region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level-1).layerCount(1);region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
            region.srcOffsets(1).set(Math.max(1,image.gpu.width>>(level-1)),Math.max(1,image.gpu.height>>(level-1)),1);region.dstOffsets(1).set(Math.max(1,image.gpu.width>>level),Math.max(1,image.gpu.height>>level),1);
            vkCmdBlitImage(cmd,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region,VK_FILTER_LINEAR);
        }
        mipToSource(cmd,stack,image,image.gpu.levels-1);image.layout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;image.stage=VK_PIPELINE_STAGE_2_BLIT_BIT;image.access=VK_ACCESS_2_TRANSFER_READ_BIT|VK_ACCESS_2_TRANSFER_WRITE_BIT;
        transition(cmd,stack,image,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }
    public void present(int framebuffer,int width,int height){beginFrame();Target source=target(framebuffer);flush(source.color());if(presentation.begin(slotIndex,width,height)){presentation.present(source.color().gpu);frames++;}endFrame();}
    public void finishOffscreenFrame(){if(presentation!=null)throw new IllegalStateException("Offscreen frame operation on a game window");beginFrame();flush(null);endFrame();}
    public void setVsync(boolean enabled){presentation.setVsync(enabled);}
    public void captureNext(String path){presentation.captureNext(path);}
    /** Explicit game screenshot/thumbnail readback. Never used to transport a normal rendered frame. */
    public byte[] readTexture(int id) {
        Texture texture=Objects.requireNonNull(textures.get(id),"Screenshot texture");if(texture.image().gpu.format!=VK_FORMAT_R8G8B8A8_UNORM)throw new UnsupportedOperationException("RGBA8 screenshot requested from format "+texture.image().gpu.format);return readTextureBytes(id,4);
    }
    public float[] readTextureFloats(int id){
        Texture texture=Objects.requireNonNull(textures.get(id),"Readback texture");int format=texture.image().gpu.format;
        int components=switch(format){case VK_FORMAT_R8G8B8A8_UNORM,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT->4;case VK_FORMAT_R16G16_SFLOAT,VK_FORMAT_R32G32_SFLOAT->2;case VK_FORMAT_R16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_D32_SFLOAT->1;default->throw new UnsupportedOperationException("Float readback format "+format);};
        boolean normalized=format==VK_FORMAT_R8G8B8A8_UNORM,half=format==VK_FORMAT_R16G16B16A16_SFLOAT||format==VK_FORMAT_R16G16_SFLOAT||format==VK_FORMAT_R16_SFLOAT;
        byte[] raw=readTextureBytes(id,components*(normalized?1:half?2:4));ByteBuffer input=ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());float[] result=new float[texture.image().gpu.width*texture.image().gpu.height*4];
        for(int offset=0;offset<result.length;offset+=4){result[offset+3]=1;for(int c=0;c<components;c++){if(normalized)result[offset+c]=(input.get()&255)/255f;else if(!half)result[offset+c]=input.getFloat();else{int bits=input.getShort()&65535,sign=(bits&32768)==0?1:-1,exponent=(bits>>>10)&31,fraction=bits&1023;result[offset+c]=sign*(exponent==0?Math.scalb((float)fraction,-24):exponent==31?fraction==0?Float.POSITIVE_INFINITY:Float.NaN:Math.scalb(1+fraction/1024f,exponent-15));}}}return result;
    }
    private byte[] readTextureBytes(int id,int pixelBytes) {
        beginFrame();flush(null);
        for(int i=0;i<FRAMES;i++)waitSlot(i);
        context.uploads.drain();long fence=slot.fence;Texture source=Objects.requireNonNull(textures.get(id),"Native readback texture");
        Image image=source.image();int width=image.gpu.width,height=image.gpu.height,size=Math.multiplyExact(Math.multiplyExact(width,height),pixelBytes),previous=image.layout;
        try(GpuBuffer storage=new GpuBuffer(context,size,VK_BUFFER_USAGE_TRANSFER_DST_BIT);var stack=MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command,0),"reset native screenshot command");check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native screenshot command");
            transition(command,stack,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);
            vkCmdCopyImageToBuffer(command,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,storage.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(r->r.aspectMask(image.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(width,height,1)));
            var host=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(storage.buffer).size(size).srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(host));
            transition(command,stack,image,previous,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT);
            check(vkEndCommandBuffer(command),"end native screenshot command");check(vkResetFences(context.device,fence),"reset native screenshot fence");
            check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),fence),"native screenshot submit");
            check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"native screenshot complete");byte[] pixels=new byte[size];storage.mapped.duplicate().get(pixels);return pixels;
        }
    }
    public long[] stats(){return new long[]{frames,draws,uploads,programs.size(),targets.size(),context.validationErrors(),terrainDraws,entityDraws};}
    public String[] deviceInfo(){try(var stack=MemoryStack.stackPush()){var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physical,properties);String vendor=switch(properties.vendorID()){case 0x10de->"NVIDIA Corporation";case 0x1002->"AMD";case 0x8086->"Intel";default->"Vulkan device vendor "+properties.vendorID();};return new String[]{properties.deviceNameString(),vendor};}}
    public Map<String,Long> diagnostics(){
        Map<String,Long> result=new LinkedHashMap<>(presentation==null?Map.of():presentation.diagnostics());result.putAll(geometry.stats());result.put("embeddiumTerrainDraws",embeddiumTerrainDraws);
        result.put("dynamicMergedDraws",dynamicMergedDraws);result.put("dynamicGpuDraws",dynamicGpuDraws);
        result.put("guiMergedDraws",guiMergedDraws);result.put("guiGpuDraws",guiGpuDraws);
        result.put("residentUnreclaimable",residentUnreclaimable);result.put("splitResidentBudget",splitResidentBudget);result.put("splitDrawLimit",splitDrawLimit);result.put("splitVertexLimit",splitVertexLimit);result.put("splitInclusiveNs",splitWaitNs);
        result.put("hotbarResidentDraws",hotbarResidentDraws);result.put("hotbarResidentBytes",hotbarResidentBytes);
        String[] names={"frames","draws","terrainDraws","entityDraws","vertexBytes","indexBytes","uniformBytes","terrainVertexBytes","terrainIndexBytes","descriptorSets","sceneSubmits","textureBytes","sceneFenceWaitNs","uploadDrainNs","flushInclusiveNs","hostCopyNs","descriptorCpuNs","sceneSubmitNs","descriptorResets","foldedClears","cachedDescriptorSets","framesInFlight","multiDrawPackets","indirectDraws","hoistedInputs","frameSplits","residentReclaims"};
        long[] values={frames,draws,terrainDraws,entityDraws,vertexBytes,indexBytes,uniformBytes,terrainVertexBytes,terrainIndexBytes,descriptorSets,sceneSubmits,textureBytes,sceneFenceWaitNs,uploadDrainNs,flushCpuNs,hostCopyNs,descriptorCpuNs,sceneSubmitNs,descriptorResets,foldedClears,Arrays.stream(slots).mapToLong(frame->frame.persistentDescriptors.size()).sum(),FRAMES,multiDrawPackets,indirectDraws,hoistedInputs,frameSplits,residentReclaims};
        for(int i=0;i<names.length;i++)result.put(names[i],values[i]);long[] upload=context.uploads.stats();result.put("uploadSubmits",upload[0]);result.put("uploadBytes",upload[2]);
        if(timers!=null){double[] metric=timers.metric(0);result.put("sceneGpuSamples",(long)metric[0]);result.put("sceneGpuNs",Math.round(metric[1]*1e6));}return result;
    }
    private void closeDiagnostics(){if(timers!=null){timers.collectAll();timers.report(report::println);timers.close();}if(markerPool!=0){report.print(gpuMarkerReport());vkDestroyQueryPool(context.device,markerPool,null);markerPool=0;}if(DIAGNOSTICS)report.println("NATIVE_DIAGNOSTICS="+diagnostics());}
    @Override public void close(){
        if(closed)return;closed=true;if(presentation!=null)presentation.close();if(context.device!=null)vkDeviceWaitIdle(context.device);
        context.uploads.drain();closeDiagnostics();
        for(Slot frame:slots){for(Runnable action:frame.retired)action.run();frame.retired.clear();}Set<Image> images=Collections.newSetFromMap(new IdentityHashMap<>());
        geometry.close();
        for(Texture texture:textures.values()){if(texture.view()!=texture.image().gpu.sampledView)vkDestroyImageView(context.device,texture.view(),null);images.add(texture.image());}for(Target target:targets.values()){for(Image color:target.attachments)if(color!=null)images.add(color);images.add(target.depth());}
        for(Image image:images)image.gpu.close();for(NativeShaderProgram program:programs.values())program.close();
        for(Slot frame:slots){for(GpuBuffer buffer:new GpuBuffer[]{frame.vertices,frame.indices,frame.uniforms,frame.constantVertices,frame.indirect})if(buffer!=null)buffer.close();vkDestroyDescriptorPool(context.device,frame.descriptors,null);vkDestroyFence(context.device,frame.fence,null);}
        for(long sampler:samplers)if(sampler!=0)vkDestroySampler(context.device,sampler,null);
        vkDestroyCommandPool(context.device,pool,null);
        report.println("FINAL_PRESENTED_FRAMES="+frames+"\nNATIVE_DRAWS="+draws+"\nCPU_TEXTURE_UPLOADS="+uploads+"\nVALIDATION_ERRORS="+context.validationErrors());
        context.close();report.println("CLOSED=true");report.close();
    }
}
