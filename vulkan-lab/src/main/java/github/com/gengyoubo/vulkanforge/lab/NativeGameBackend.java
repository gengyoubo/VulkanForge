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

/** Ordered game draw/resource executor. All incoming objects are CPU data; every GPU object is private Vulkan. */
public final class NativeGameBackend implements AutoCloseable {
    private static final boolean DIAGNOSTICS=Boolean.getBoolean("vulkanforge.nativeDiagnostics"),PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private static final int MAX_DRAWS=16384;
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
        final GpuImage gpu;final boolean depth;int layout=VK_IMAGE_LAYOUT_UNDEFINED;
        Image(GpuImage gpu,boolean depth){this.gpu=gpu;this.depth=depth;}
    }
    private record Texture(Image image,long sampler) { }
    private record Target(Image color,Image depth,int width,int height) { }
    private final Map<Integer,Texture> textures=new HashMap<>();
    private final Map<Integer,Target> targets=new HashMap<>();
    private final List<Runnable> retired=new ArrayList<>();
    private final List<Object[]> commands=new ArrayList<>();
    private final long[] samplers=new long[4];
    private long pool,fence,descriptors,frames,draws,uploads,terrainDraws,entityDraws;
    private VkCommandBuffer command;
    private GpuBuffer vertices,indices,uniforms;
    private boolean pending,frameOpen,closed;
    private GpuTimers timers;
    private long vertexBytes,indexBytes,uniformBytes,terrainVertexBytes,terrainIndexBytes,descriptorSets,sceneSubmits,textureBytes;
    private long sceneFenceWaitNs,uploadDrainNs,flushCpuNs,hostCopyNs,descriptorCpuNs,sceneSubmitNs;
    private static long stamp(){return PROFILE?System.nanoTime():0;}
    private static long elapsed(long start){return PROFILE?System.nanoTime()-start:0;}
    public NativeGameBackend(String reportPath,long hwnd,long hinstance,int api,String extensions,boolean validation)throws IOException {
        Path path=Path.of(reportPath).toAbsolutePath();Files.createDirectories(path.getParent());report=new PrintWriter(Files.newBufferedWriter(path),true);
        context=new VulkanContext(api,extensions);geometry=new NativeGeometryCache(context,retired::add);NativeSwapchain created=null;
        try {
            context.initializeNativeWin32(hwnd,hinstance,validation);created=new NativeSwapchain(context,report::println,true);
            try(var stack=MemoryStack.stackPush()) {
                var h=stack.mallocLong(1);
                check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,h),"native game command pool");pool=h.get(0);
                var p=stack.mallocPointer(1);check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),p),"native game command");command=new VkCommandBuffer(p.get(0),context.device);
                check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,h),"native game fence");fence=h.get(0);
                var sizes=VkDescriptorPoolSize.calloc(2,stack);sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_DRAWS*16);sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(MAX_DRAWS);
                check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_DRAWS).pPoolSizes(sizes),null,h),"native game descriptor pool");descriptors=h.get(0);
                for(int i=0;i<4;i++){check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().minFilter((i&1)!=0?VK_FILTER_LINEAR:VK_FILTER_NEAREST).magFilter((i&1)!=0?VK_FILTER_LINEAR:VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU((i&2)!=0?VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE:VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV((i&2)!=0?VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE:VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),null,h),"native game sampler");samplers[i]=h.get(0);}
            }
            report.println("BACKEND=VK_NATIVE_GAME\nOPENGL_CONTEXTS=0\nSHARED_IMAGES=0\nEXTERNAL_SEMAPHORES=0\nVALIDATION="+(context.validationEnabled()?"ENABLED":"UNAVAILABLE"));
            if(PROFILE)timers=new GpuTimers(context,1,report::println,"NATIVE_SCENE");
        }catch(RuntimeException|Error error){if(created!=null)created.close();context.close();report.close();throw error;}
        presentation=created;
        texture(0,1,1,0,new byte[]{-1,-1,-1,-1});
    }
    public void beginFrame() {
        if(closed)throw new IllegalStateException("Native game backend closed");if(frameOpen)return;
        if(pending){long start=stamp();check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"native game frame complete");sceneFenceWaitNs+=elapsed(start);pending=false;if(timers!=null)timers.collect(0);}
        presentation.waitSlot(0);long drainStart=stamp();context.uploads.drain();uploadDrainNs+=elapsed(drainStart);
        for(Runnable action:retired)action.run();retired.clear();frameOpen=true;
    }
    public String[] program(int id,String vertex,String fragment,Object[] bindings) {
        beginFrame();Map<String,Integer> attributes=new LinkedHashMap<>();for(Object binding:bindings){Object[] pair=(Object[])binding;attributes.put((String)pair[0],(Integer)pair[1]);}
        NativeShaderProgram next=new NativeShaderProgram(context,vertex,fragment,attributes),old=programs.put(id,next);if(old!=null)retired.add(old::close);
        return next.samplers.toArray(String[]::new);
    }
    public void deleteProgram(int id){beginFrame();NativeShaderProgram old=programs.remove(id);if(old!=null)retired.add(old::close);}
    public void texture(int id,int width,int height,int flags,byte[] rgba) {
        beginFrame();if(width<=0||height<=0||(long)width*height*4!=rgba.length)throw new IllegalArgumentException("Invalid native texture "+id);
        GpuImage gpu=new GpuImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT);
        Image image=new Image(gpu,false);
        context.uploads.submit(rgba,(cmd,staging,offset,stack)->{
            transition(cmd,stack,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
            vkCmdCopyBufferToImage(cmd,staging.buffer,gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkBufferImageCopy.calloc(1,stack).bufferOffset(offset).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(width,height,1)));
            transition(cmd,stack,image,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        });
        Texture old=textures.put(id,new Texture(image,samplers[flags&3]));if(old!=null&&!isTarget(old.image()))retired.add(old.image().gpu::close);uploads++;if(DIAGNOSTICS)textureBytes+=rgba.length;
    }
    public void textureSampler(int id,int flags){Texture texture=textures.get(id);if(texture!=null)textures.put(id,new Texture(texture.image(),samplers[flags&3]));}
    public void deleteTexture(int id){beginFrame();Texture old=textures.remove(id);if(old!=null&&!isTarget(old.image()))retired.add(old.image().gpu::close);}
    private boolean isTarget(Image image){for(Target target:targets.values())if(target.color()==image||target.depth()==image)return true;return false;}
    public void target(int id,int colorId,int depthId,int width,int height) {
        beginFrame();Target old=targets.get(id);if(old!=null&&old.width()==width&&old.height()==height)return;
        Image color=new Image(new GpuImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_IMAGE_ASPECT_COLOR_BIT),false);
        Image depth=new Image(new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_IMAGE_ASPECT_DEPTH_BIT),true);
        Target next=new Target(color,depth,width,height);targets.put(id,next);textures.put(colorId,new Texture(color,samplers[0]));if(depthId>0)textures.put(depthId,new Texture(depth,samplers[0]));if(old!=null)retired.add(()->{old.color().gpu.close();old.depth().gpu.close();});
    }
    public void deleteTarget(int id){beginFrame();Target old=targets.remove(id);if(old!=null)retired.add(()->{old.color().gpu.close();old.depth().gpu.close();});}
    private Target target(int id){Target target=targets.get(id);if(target==null)throw new IllegalStateException("Uninitialized native framebuffer "+id);return target;}
    public void clear(int id,int mask,float[] color,float depth,int[] scissor){beginFrame();commands.add(new Object[]{1,target(id),mask,color.clone(),depth,scissor.clone()});}
    public void blit(int source,int destination,int[] rectangle,int mask,int filter){beginFrame();commands.add(new Object[]{2,target(source),target(destination),rectangle.clone(),mask,filter});}
    /** Packet includes immutable CPU data and optional {vertex id/version,index id/version,index byte offset}. */
    public void draw(int framebuffer,Object[] packet) {
        beginFrame();NativeShaderProgram program=programs.get((Integer)packet[0]);if(program==null)throw new IllegalStateException("Missing native shader program "+packet[0]);
        int[] ids=(int[])packet[11];Texture[] inputs=new Texture[ids.length];for(int i=0;i<ids.length;i++){inputs[i]=textures.get(ids[i]);if(inputs[i]==null)throw new IllegalStateException("Missing native texture "+ids[i]);}
        NativeGeometryCache.Buffer vertex=null,index=null;
        if(program.terrain&&packet.length>12){long[] resource=(long[])packet[12];vertex=geometry.get((int)resource[0],resource[1],(byte[])packet[1]);index=geometry.get((int)resource[2],resource[3],(byte[])packet[2]);}
        commands.add(new Object[]{0,target(framebuffer),program,packet,inputs,vertex,index});
    }
    public void deleteBuffer(int id){beginFrame();geometry.delete(id);}
    private static int indexLength(Object[] packet){return Math.multiplyExact((Integer)packet[4],(Integer)packet[3]==5123?2:4);}
    private static int indexStart(Object[] packet){return packet.length>12?Math.toIntExact(((long[])packet[12])[4]):0;}
    private GpuBuffer ensure(GpuBuffer buffer,int size,int usage){if(buffer!=null&&buffer.mapped.capacity()>=size)return buffer;if(buffer!=null)buffer.close();return new GpuBuffer(context,Math.max(4096,Integer.highestOneBit(Math.max(1,size-1))<<1),usage);}
    private void flush() {
        if(commands.isEmpty())return;
        long flushStart=stamp();
        int vb=0,ib=0,count=0;for(Object[] entry:commands)if((Integer)entry[0]==0){Object[] p=(Object[])entry[3];if(entry[5]==null)vb=Math.addExact(vb,((byte[])p[1]).length);if(entry[6]==null)ib=Math.addExact(ib,(indexLength(p)+3)&~3);count++;}
        if(count>MAX_DRAWS||vb>256*1024*1024)throw new UnsupportedOperationException("Native frame upload budget exceeded; no draws discarded");
        vertices=ensure(vertices,vb,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);indices=ensure(indices,ib,VK_BUFFER_USAGE_INDEX_BUFFER_BIT);uniforms=ensure(uniforms,count*1024,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        ByteBuffer vertexData=vertices.mapped.duplicate().clear(),indexData=indices.mapped.duplicate().clear();FloatBuffer values=uniforms.mapped.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        try(var frameStack=MemoryStack.stackPush()) {
            check(vkResetDescriptorPool(context.device,descriptors,0),"reset native game descriptors");check(vkResetCommandBuffer(command,0),"reset native game command");
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(frameStack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native game command");
            if(timers!=null){timers.begin(command,0);timers.start(command,0,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
            Target active=null;int drawIndex=0;long boundPipeline=0,boundIndex=0,boundIndexOffset=-1;int boundIndexType=-1;int[] previousArea=null;float previousBias=Float.NaN,previousSlope=Float.NaN;
            Map<DescriptorKey,Long> descriptorCache=new HashMap<>();
            for(Object[] entry:commands) {
                try(var stack=MemoryStack.stackPush()) {
                int kind=(Integer)entry[0];
                if(kind==0) {
                    Target output=(Target)entry[1];NativeShaderProgram program=(NativeShaderProgram)entry[2];Object[] p=(Object[])entry[3];Texture[] inputs=(Texture[])entry[4];
                    boolean transition=false;for(Texture input:inputs){if(input.image()==output.color()||input.image()==output.depth())throw new UnsupportedOperationException("Native framebuffer feedback");if(input.image().layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)transition=true;}
                    if(active!=output||transition){if(active!=null)vkCmdEndRendering(command);for(Texture input:inputs)transition(command,stack,input.image(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);begin(output,stack);active=output;}
                    long copyStart=stamp();
                    byte[] vertexBytes=(byte[])p[1],indexBytes=(byte[])p[2];int vertexOffset=vertexData.position(),indexOffset=indexData.position(),indexLength=indexLength(p);
                    NativeGeometryCache.Buffer residentVertex=(NativeGeometryCache.Buffer)entry[5],residentIndex=(NativeGeometryCache.Buffer)entry[6];
                    if(residentVertex==null)vertexData.put(vertexBytes);
                    if(residentIndex==null)indexData.put(indexBytes,indexStart(p),indexLength).position((indexData.position()+3)&~3);
                    program.writeValues((Object[])p[10],values,drawIndex*256);
                    hostCopyNs+=elapsed(copyStart);
                    if(DIAGNOSTICS){if(residentVertex==null)this.vertexBytes+=vertexBytes.length;if(residentIndex==null)this.indexBytes+=indexLength;uniformBytes+=1024;if(program.terrain){if(residentVertex==null)terrainVertexBytes+=vertexBytes.length;if(residentIndex==null)terrainIndexBytes+=indexLength;}}
                    int[] state=(int[])p[7],viewport=(int[])p[8];float[] raster=(float[])p[9];
                    long pipeline=program.pipeline(state,(int[][])p[6],(Integer)p[5]);
                    if(pipeline!=boundPipeline){vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);boundPipeline=pipeline;}
                    if(!Arrays.equals(previousArea,viewport)){
                        vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(viewport[0]).y(viewport[1]).width(viewport[2]).height(viewport[3]).maxDepth(1));
                        vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).offset(o->o.set(viewport[4],viewport[5])).extent(e->e.set(viewport[6],viewport[7])));previousArea=viewport;
                    }
                    if(previousBias!=raster[1]||previousSlope!=raster[0]){vkCmdSetDepthBias(command,raster[1],0,raster[0]);previousBias=raster[1];previousSlope=raster[0];}
                    long descriptorStart=stamp();
                    DescriptorKey key=new DescriptorKey(program,inputs);Long binding=descriptorCache.get(key);
                    if(binding==null){
                    var descriptor=stack.mallocLong(1);check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptors).pSetLayouts(stack.longs(program.textureLayout)),descriptor),"native draw descriptors");
                    var writes=VkWriteDescriptorSet.calloc(inputs.length+1,stack);
                    for(int i=0;i<inputs.length;i++){Texture input=inputs[i];writes.get(i).sType$Default().dstSet(descriptor.get(0)).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(input.sampler()).imageView(input.image().gpu.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));}
                    writes.get(inputs.length).sType$Default().dstSet(descriptor.get(0)).dstBinding(inputs.length).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.buffer).range(1024));vkUpdateDescriptorSets(context.device,writes,null);
                    binding=descriptor.get(0);descriptorCache.put(key,binding);descriptorSets++;
                    }
                    descriptorCpuNs+=elapsed(descriptorStart);
                    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,program.layout,0,stack.longs(binding),stack.ints(drawIndex*1024));
                    vkCmdBindVertexBuffers(command,0,stack.longs(residentVertex==null?vertices.buffer:residentVertex.gpu.buffer),stack.longs(residentVertex==null?vertexOffset:0));
                    long indexBuffer=residentIndex==null?indices.buffer:residentIndex.gpu.buffer,indexByteOffset=residentIndex==null?indexOffset:indexStart(p);int indexType=(Integer)p[3]==5123?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32;
                    if(boundIndex!=indexBuffer||boundIndexOffset!=indexByteOffset||boundIndexType!=indexType){vkCmdBindIndexBuffer(command,indexBuffer,indexByteOffset,indexType);boundIndex=indexBuffer;boundIndexOffset=indexByteOffset;boundIndexType=indexType;}
                    vkCmdDrawIndexed(command,(Integer)p[4],1,0,0,0);draws++;if(program.terrain)terrainDraws++;if(program.entity)entityDraws++;drawIndex++;
                } else {
                    if(active!=null){vkCmdEndRendering(command);active=null;}
                    if(kind==1){Target output=(Target)entry[1];begin(output,stack);int mask=(Integer)entry[2];var clears=VkClearAttachment.calloc(((mask&16384)!=0?1:0)+((mask&256)!=0?1:0),stack);int at=0;
                        if((mask&16384)!=0){var c=clears.get(at++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);float[] color=(float[])entry[3];for(int i=0;i<4;i++)c.clearValue().color().float32(i,color[i]);}
                        if((mask&256)!=0)clears.get(at).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).clearValue().depthStencil().depth((Float)entry[4]);
                        int[] rect=(int[])entry[5];var area=VkClearRect.calloc(1,stack).layerCount(1);area.rect().offset().set(rect[0],rect[1]);area.rect().extent().set(rect[2],rect[3]);if(clears.remaining()>0)vkCmdClearAttachments(command,clears,area);vkCmdEndRendering(command);
                    }else if(kind==2){Target from=(Target)entry[1],to=(Target)entry[2];int[] rect=(int[])entry[3];int mask=(Integer)entry[4];for(boolean depth:new boolean[]{false,true})if((mask&(depth?256:16384))!=0){Image src=depth?from.depth():from.color(),dst=depth?to.depth():to.color();transition(command,stack,src,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_BLIT_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);transition(command,stack,dst,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_BLIT_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);var copy=VkImageBlit.calloc(1,stack);int aspect=depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;copy.srcSubresource().aspectMask(aspect).layerCount(1);copy.dstSubresource().aspectMask(aspect).layerCount(1);copy.srcOffsets(0).set(rect[0],rect[1],0);copy.srcOffsets(1).set(rect[2],rect[3],1);copy.dstOffsets(0).set(rect[4],rect[5],0);copy.dstOffsets(1).set(rect[6],rect[7],1);vkCmdBlitImage(command,src.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,dst.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy,!depth&&(Integer)entry[5]==9729?VK_FILTER_LINEAR:VK_FILTER_NEAREST);}}
                }
                }
            }
            if(active!=null)vkCmdEndRendering(command);
            // The presentation pass consumes the final color in COLOR_ATTACHMENT_OPTIMAL.
            for(Target target:targets.values())if(target.color().layout!=VK_IMAGE_LAYOUT_UNDEFINED)transition(command,frameStack,target.color(),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            if(timers!=null)timers.end(command,0,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command),"end native game command");context.uploads.flush();check(vkResetFences(context.device,fence),"reset native game fence");
            long submitStart=stamp();check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,frameStack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,frameStack).sType$Default().commandBuffer(command)),fence),"native game submit");sceneSubmitNs+=elapsed(submitStart);sceneSubmits++;pending=true;if(timers!=null)timers.submitted(0);
        }
        commands.clear();
        flushCpuNs+=elapsed(flushStart);
    }
    private void begin(Target target,MemoryStack stack) {
        boolean colorNew=target.color().layout==VK_IMAGE_LAYOUT_UNDEFINED,depthNew=target.depth().layout==VK_IMAGE_LAYOUT_UNDEFINED;
        transition(command,stack,target.color(),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        transition(command,stack,target.depth(),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        var color=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(target.color().gpu.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(colorNew?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(target.depth().gpu.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(depthNew?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);depth.clearValue().depthStencil().depth(1);
        vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth).renderArea(r->r.extent(e->e.set(target.width(),target.height()))));
    }
    private static void transition(VkCommandBuffer cmd,MemoryStack stack,Image image,int layout,long stage,long access) {
        if(image.layout==layout&&layout==VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)return;
        var barrier=VkImageMemoryBarrier2.calloc(1,stack).sType$Default().image(image.gpu.image).oldLayout(image.layout).newLayout(layout)
                .srcStageMask(image.layout==VK_IMAGE_LAYOUT_UNDEFINED?VK_PIPELINE_STAGE_2_NONE:VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(image.layout==VK_IMAGE_LAYOUT_UNDEFINED?0:VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT).dstStageMask(stage).dstAccessMask(access)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).subresourceRange(r->r.aspectMask(image.depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));image.layout=layout;
    }
    public void present(int framebuffer,int width,int height){beginFrame();flush();Target source=target(framebuffer);if(presentation.begin(0,width,height)){presentation.present(source.color().gpu);frames++;}frameOpen=false;}
    public void setVsync(boolean enabled){presentation.setVsync(enabled);}
    public void captureNext(String path){presentation.captureNext(path);}
    /** Explicit game screenshot/thumbnail readback. Never used to transport a normal rendered frame. */
    public byte[] readTexture(int id) {
        beginFrame();flush();
        if(pending){check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"wait native screenshot source");pending=false;if(timers!=null)timers.collect(0);}
        context.uploads.drain();Texture source=textures.get(id);if(source==null||source.image().depth)throw new UnsupportedOperationException("Native screenshot requires a color texture");
        Image image=source.image();int width=image.gpu.width,height=image.gpu.height,size=Math.multiplyExact(Math.multiplyExact(width,height),4),previous=image.layout;
        try(GpuBuffer storage=new GpuBuffer(context,size,VK_BUFFER_USAGE_TRANSFER_DST_BIT);var stack=MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command,0),"reset native screenshot command");check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native screenshot command");
            transition(command,stack,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);
            vkCmdCopyImageToBuffer(command,image.gpu.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,storage.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(width,height,1)));
            var host=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(storage.buffer).size(size).srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(host));
            transition(command,stack,image,previous,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT);
            check(vkEndCommandBuffer(command),"end native screenshot command");check(vkResetFences(context.device,fence),"reset native screenshot fence");
            check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),fence),"native screenshot submit");
            check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"native screenshot complete");byte[] pixels=new byte[size];storage.mapped.duplicate().get(pixels);return pixels;
        }
    }
    public long[] stats(){return new long[]{frames,draws,uploads,programs.size(),targets.size(),context.validationErrors(),terrainDraws,entityDraws};}
    public Map<String,Long> diagnostics(){
        Map<String,Long> result=new LinkedHashMap<>(presentation.diagnostics());result.putAll(geometry.stats());
        String[] names={"frames","draws","terrainDraws","entityDraws","vertexBytes","indexBytes","uniformBytes","terrainVertexBytes","terrainIndexBytes","descriptorSets","sceneSubmits","textureBytes","sceneFenceWaitNs","uploadDrainNs","flushInclusiveNs","hostCopyNs","descriptorCpuNs","sceneSubmitNs"};
        long[] values={frames,draws,terrainDraws,entityDraws,vertexBytes,indexBytes,uniformBytes,terrainVertexBytes,terrainIndexBytes,descriptorSets,sceneSubmits,textureBytes,sceneFenceWaitNs,uploadDrainNs,flushCpuNs,hostCopyNs,descriptorCpuNs,sceneSubmitNs};
        for(int i=0;i<names.length;i++)result.put(names[i],values[i]);long[] upload=context.uploads.stats();result.put("uploadSubmits",upload[0]);result.put("uploadBytes",upload[2]);
        if(timers!=null){double[] metric=timers.metric(0);result.put("sceneGpuSamples",(long)metric[0]);result.put("sceneGpuNs",Math.round(metric[1]*1e6));}return result;
    }
    private void closeDiagnostics(){if(timers!=null){timers.collectAll();timers.report(report::println);timers.close();}if(DIAGNOSTICS)report.println("NATIVE_DIAGNOSTICS="+diagnostics());}
    @Override public void close(){
        if(closed)return;closed=true;presentation.close();if(context.device!=null)vkDeviceWaitIdle(context.device);
        context.uploads.drain();closeDiagnostics();
        for(Runnable action:retired)action.run();retired.clear();Set<Image> images=Collections.newSetFromMap(new IdentityHashMap<>());
        geometry.close();
        for(Texture texture:textures.values())images.add(texture.image());for(Target target:targets.values()){images.add(target.color());images.add(target.depth());}
        for(Image image:images)image.gpu.close();for(NativeShaderProgram program:programs.values())program.close();
        if(vertices!=null)vertices.close();if(indices!=null)indices.close();if(uniforms!=null)uniforms.close();
        for(long sampler:samplers)if(sampler!=0)vkDestroySampler(context.device,sampler,null);
        vkDestroyDescriptorPool(context.device,descriptors,null);vkDestroyFence(context.device,fence,null);vkDestroyCommandPool(context.device,pool,null);
        report.println("FINAL_PRESENTED_FRAMES="+frames+"\nNATIVE_DRAWS="+draws+"\nCPU_TEXTURE_UPLOADS="+uploads+"\nVALIDATION_ERRORS="+context.validationErrors());
        context.close();report.println("CLOSED=true");report.close();
    }
}
