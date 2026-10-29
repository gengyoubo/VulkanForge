package dev.vulkanforge.bootstrap;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.nio.file.*;
import java.util.regex.*;

/** Opt-in JVM bootstrap: disable Forge's GL early window and fail every real GL entry point. */
public final class NativeBootstrap {
    private static final String STATE = "github/com/gengyoubo/vulkanforge/client/NativeDeviceState";
    private static final String WINDOW = "github/com/gengyoubo/vulkanforge/client/NativeGameWindow";
    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("vulkanforge.nativeBootstrapAvailable", "true");
        Path directory=arguments==null||arguments.isBlank()?Path.of(""):Path.of(arguments);
        String config="";
        try {Path file=directory.resolve("config/vulkanforge-client.toml");if(Files.isRegularFile(file))config=Files.readString(file);}
        catch(java.io.IOException ignored) { /* Match the client reader's default when the file is unreadable. */ }
        String mode=nativeStartup(System.getProperty("vulkanforge.startup"),config);
        if(mode==null)return; // Inert for GL/GL_VK, including an ordinary renderer restart.
        System.setProperty("vulkanforge.startup", mode);
        System.setProperty("vulkanforge.nativeBootstrap", "true");
        System.setProperty("vulkanforge.forbiddenGlCalls", "0");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                try { return rewrite(name, bytes); }
                catch (Throwable error) {
                    System.setProperty("vulkanforge.forbiddenGlCalls", "TRANSFORM_FAILURE:" + name);
                    System.err.println("Native bootstrap transformation failed: " + name); error.printStackTrace();
                    // Transformer exceptions are ignored by the JVM. Invalid bytes force class definition to fail closed.
                    return new byte[]{0};
                }
            }
        });
    }
    static String nativeStartup(String override,String config) {
        String saved=setting(config,"renderer","mode",setting(config,"renderer","backend","GL_VK"));
        String raw=override==null||override.isBlank()?saved:override.split(":",2)[0];
        String selected=raw.trim().toUpperCase(Locale.ROOT).replace('-','_');
        boolean nativeMode=switch(selected){case "VK","VK_NATIVE"->true;case "GL","OPENGL","GL_VK","VULKAN","HYBRID","GLVK_HYBRID"->false;default->saved.equalsIgnoreCase("VK")||saved.equalsIgnoreCase("VK_NATIVE");};
        if(!nativeMode)return null;
        String version=setting(config,"vulkan","version","V1_4");
        if(override!=null&&override.contains(":"))version=override.split(":",2)[1];
        version=version.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("V1_2","V1_3","V1_4").contains(version))version="V1_4";
        return "VK:"+version;
    }
    private static String setting(String source,String section,String key,String fallback) {
        boolean inside=false;
        Pattern header=Pattern.compile("^\\[([^]]+)]\\s*(?:#.*)?$"),entry=Pattern.compile("^"+Pattern.quote(key)+"\\s*=\\s*(['\"])(.*?)\\1\\s*(?:#.*)?$");
        for(String line:source.split("\\R")){line=line.strip();Matcher match=header.matcher(line);if(match.matches()){inside=match.group(1).equals(section);continue;}if(inside){match=entry.matcher(line);if(match.matches())return match.group(2);}}
        return fallback;
    }
    static byte[] rewrite(String name, byte[] bytes) {
        if (name == null) return null;
        // Capabilities is an immutable data carrier used by compatibility frontends. No GL function pointer is invoked.
        if(name.equals("org/lwjgl/opengl/GLCapabilities"))return null;
        boolean gl = name.startsWith("org/lwjgl/opengl/");
        boolean early = name.equals("net/minecraftforge/fml/loading/ImmediateWindowHandler");
        boolean glfw = name.equals("org/lwjgl/glfw/GLFW");
        boolean embeddium = name.startsWith("me/jellysquid/mods/sodium/") || name.startsWith("org/embeddedt/embeddium/");
        boolean modOptimizations=(Boolean.getBoolean("vulkanforge.nativeModSubmission")||Boolean.getBoolean("vulkanforge.nativeRegionBridge"))&&!Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission");
        boolean topK=(Boolean.getBoolean("vulkanforge.nativeModSubmission")||Boolean.getBoolean("vulkanforge.nativeChlorideTopK"))&&!Boolean.getBoolean("vulkanforge.nativeLegacyModSubmission");
        boolean chlorideLimit=name.equals("me/srrapero720/chloride/mixins/impl/EntityDistanceCullingMixin$LevelRendererEntityMixin");
        boolean profileCulling=name.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher")&&Boolean.getBoolean("vulkanforge.nativeProfile");
        boolean profileChunks=Boolean.getBoolean("vulkanforge.nativeProfile")&&(name.equals("me/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager")||name.equals("me/jellysquid/mods/sodium/client/render/chunk/region/RenderRegionManager"));
        boolean hudHooks=Boolean.getBoolean("vulkanforge.hudDiagnostics")&&(name.equals("net/minecraft/client/gui/Gui")||name.equals("net/minecraftforge/client/gui/overlay/ForgeGui")||name.equals("net/minecraft/client/renderer/ItemInHandRenderer"));
        boolean guiBlitHooks=Boolean.getBoolean("vulkanforge.nativeGuiFastPath")&&name.equals("net/minecraft/client/gui/GuiGraphics");
        boolean hotbarCache=Boolean.getBoolean("vulkanforge.nativeHotbarCache");
        boolean game = name.startsWith("com/mojang/blaze3d/") || name.startsWith("net/minecraft/client/")
                || name.startsWith("net/minecraftforge/client/") || name.startsWith("github/com/gengyoubo/vulkanforge/client/")
                || name.startsWith("net/irisshaders/") || name.startsWith("net/coderbot/iris/") || name.startsWith("net/coderbot/batchedentityrendering/") || embeddium;
        if (!gl && !early && !glfw && !game && !(chlorideLimit&&topK) && !profileCulling) return null;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, profileCulling||profileChunks||hudHooks||guiBlitHooks?ClassReader.EXPAND_FRAMES:0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            int hudScope=hudHooks?hudScope(name,method):-1;if(hudScope>=0){instrumentHud(node.name,method,hudScope);changed=true;}
            if(guiBlitHooks&&method.desc.equals("(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V")){
                InsnList code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));int at=1;for(Type type:Type.getArgumentTypes(method.desc)){code.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),at));at+=type.getSize();}
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"github/com/gengyoubo/vulkanforge/client/NativeGuiBlitter","tryBlit","(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)Z",false));LabelNode fallback=new LabelNode();code.add(new JumpInsnNode(Opcodes.IFEQ,fallback));code.add(new InsnNode(Opcodes.RETURN));code.add(fallback);
                code.add(new FrameNode(Opcodes.F_NEW,11,new Object[]{node.name,"net/minecraft/resources/ResourceLocation",Opcodes.INTEGER,Opcodes.INTEGER,Opcodes.INTEGER,Opcodes.INTEGER,Opcodes.INTEGER,Opcodes.FLOAT,Opcodes.FLOAT,Opcodes.FLOAT,Opcodes.FLOAT},0,null));method.instructions.insert(code);changed=true;
            }
            // Mixin reads its classes as resources, outside ClassFileTransformer. Observe/replace the merged target methods.
            boolean chlorideSort=chlorideLimit||name.equals("net/minecraft/client/renderer/LevelRenderer")&&method.desc.equals("(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/lang/Iterable;")&&referencesFieldOwner(method,"me/srrapero720/chloride/ChlorideConfig");
            int cullingAddon=referencesFieldOwner(method,"me/srrapero720/chloride/ChlorideConfig")?0:referencesFieldOwner(method,"toni/sodiumextras/EmbyConfig")?1:-1;
            int chunkScope=profileChunks&&method.name.equals("createTerrainRenderList")?0:profileChunks&&method.name.equals("processChunkBuildResults")?1:profileChunks&&method.name.equals("uploadMeshes")&&method.desc.equals("(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V")?2:-1;
            if(chunkScope>=0){int timer=addProfileTimer(method);for(AbstractInsnNode exit:method.instructions.toArray())if(exit.getOpcode()==Opcodes.RETURN){InsnList end=new InsnList();end.add(new LdcInsnNode(chunkScope));end.add(new VarInsnNode(Opcodes.LLOAD,timer));if(chunkScope==0)end.add(new InsnNode(Opcodes.ICONST_1));else{end.add(new VarInsnNode(Opcodes.ALOAD,chunkScope==1?1:2));end.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"java/util/Collection","size","()I",true));}end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"github/com/gengyoubo/vulkanforge/client/NativeModDiagnostics","chunkScope","(IJI)V",false));method.instructions.insertBefore(exit,end);}changed=true;}
            if(profileCulling&&cullingAddon>=0&&method.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V")){
                int timer=addProfileTimer(method);
                for(AbstractInsnNode exit:method.instructions.toArray())if(exit.getOpcode()==Opcodes.RETURN){InsnList end=new InsnList();end.add(new LdcInsnNode(cullingAddon));end.add(new VarInsnNode(Opcodes.LLOAD,timer));end.add(new VarInsnNode(Opcodes.ALOAD,1));end.add(new VarInsnNode(Opcodes.ALOAD,9));end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"github/com/gengyoubo/vulkanforge/client/NativeModDiagnostics","cullingResult","(IJLjava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.insertBefore(exit,end);}changed=true;
            }
            if(modOptimizations&&name.equals("me/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer")&&method.name.equals("executeDrawBatch")&&method.desc.equals("(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Lme/jellysquid/mods/sodium/client/gl/tessellation/GlTessellation;Lme/jellysquid/mods/sodium/client/gl/device/MultiDrawBatch;)V")){
                method.instructions.clear();method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();
                for(int i=0;i<3;i++)method.instructions.add(new VarInsnNode(Opcodes.ALOAD,i));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"github/com/gengyoubo/vulkanforge/client/NativeEmbeddiumBridge","draw","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.add(new InsnNode(Opcodes.RETURN));changed=true;continue;
            }
            if(name.equals("me/jellysquid/mods/sodium/mixin/SodiumMixinPlugin")&&method.name.equals("isMixinEnabled")&&method.desc.equals("(Ljava/lang/String;)Z")) {
                // Plugin-supplied mixins bypass shouldApplyMixin in Mixin 0.8, so filter its list at the existing selector too.
                InsnList filter=new InsnList();LabelNode original=new LabelNode();filter.add(new VarInsnNode(Opcodes.ALOAD,1));filter.add(new LdcInsnNode("workarounds."));
                filter.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/String","startsWith","(Ljava/lang/String;)Z",false));filter.add(new JumpInsnNode(Opcodes.IFEQ,original));filter.add(new InsnNode(Opcodes.ICONST_0));filter.add(new InsnNode(Opcodes.IRETURN));filter.add(original);filter.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));method.instructions.insert(filter);changed=true;
            }
            if(name.equals("me/jellysquid/mods/sodium/mixin/SodiumMixinPlugin")&&method.name.equals("shouldApplyMixin")&&method.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Z")) {
                // Vulkan owns the NO_API window. Keep Embeddium's terrain/meshing mixins; its WGL context workarounds cannot run.
                method.instructions.clear();method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();method.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));method.instructions.add(new LdcInsnNode("me.jellysquid.mods.sodium.mixin.workarounds."));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/String","startsWith","(Ljava/lang/String;)Z",false));method.instructions.add(new InsnNode(Opcodes.ICONST_1));method.instructions.add(new InsnNode(Opcodes.IXOR));method.instructions.add(new InsnNode(Opcodes.IRETURN));changed=true;continue;
            }
            if(name.equals("me/jellysquid/mods/sodium/client/gl/functions/BufferStorageFunctions")&&method.name.equals("pickBest")) {
                // Embeddium's ordinary staging uploads feed CPU-owned arena versions. Vulkan owns the real upload ring and fences.
                method.instructions.clear();method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new InsnNode(Opcodes.ARETURN));method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();changed=true;continue;
            }
            if(name.equals("me/jellysquid/mods/sodium/client/gl/arena/staging/MappedStagingBuffer")&&method.name.equals("isSupported")) {
                method.instructions.clear();method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new InsnNode(Opcodes.IRETURN));method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();changed=true;continue;
            }
            if(name.equals("me/jellysquid/mods/sodium/client/gui/SodiumGameOptionPages")&&method.name.equals("supportsNoErrorContext")) {
                method.instructions.clear();method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new InsnNode(Opcodes.IRETURN));method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();changed=true;continue;
            }
            if (gl && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                if(method.name.equals("<clinit>")){method.instructions.clear();method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();method.instructions.add(new InsnNode(Opcodes.RETURN));changed=true;continue;}
                trap(method, name + "." + method.name); changed = true; continue;
            }
            if (glfw && method.name.equals("glfwMakeContextCurrent")) {
                trap(method, "glfwMakeContextCurrent"); changed = true; continue;
            }
            if (glfw && (method.name.equals("glfwCreateWindow")||method.name.equals("nglfwCreateWindow")) && (method.access & Opcodes.ACC_NATIVE)==0) {
                InsnList noApi=new InsnList();noApi.add(new LdcInsnNode(139265));noApi.add(new InsnNode(Opcodes.ICONST_0));
                noApi.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/lwjgl/glfw/GLFW","glfwWindowHint","(II)V",false));
                method.instructions.insert(noApi);changed=true;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if(hotbarCache&&instruction instanceof MethodInsnNode cachedCall){
                    String hook="github/com/gengyoubo/vulkanforge/client/NativeHotbarCache";
                    if(name.equals("net/minecraft/client/gui/Gui")&&method.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;IIFLnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V")
                            &&cachedCall.owner.equals("net/minecraft/client/gui/GuiGraphics")&&cachedCall.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V")){
                        cachedCall.setOpcode(Opcodes.INVOKESTATIC);cachedCall.owner=hook;cachedCall.name="renderItem";cachedCall.desc="(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V";cachedCall.itf=false;changed=true;continue;
                    }

                }
                if(topK&&chlorideSort&&instruction instanceof MethodInsnNode sort&&sort.name.equals("sort")&&sort.owner.equals("java/util/List")&&sort.desc.equals("(Ljava/util/Comparator;)V")){
                    sort.setOpcode(Opcodes.INVOKESTATIC);sort.owner="github/com/gengyoubo/vulkanforge/client/NativeModOptimizations";sort.name="sortLimitedEntities";sort.desc="(Ljava/util/List;Ljava/util/Comparator;)V";sort.itf=false;changed=true;continue;
                }
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (early && method.name.equals("load") && call.owner.endsWith("/FMLConfig") && call.name.equals("getBoolConfigValue")) {
                    InsnList noWindow = new InsnList(); noWindow.add(new InsnNode(Opcodes.POP)); noWindow.add(new InsnNode(Opcodes.ICONST_0));
                    method.instructions.insertBefore(call, noWindow); method.instructions.remove(call); changed = true;
                } else if (game && call.owner.startsWith("org/lwjgl/opengl/")) {
                    replaceCall(method, call); changed = true;
                } else if (game && !name.equals(WINDOW) && call.owner.equals("org/lwjgl/glfw/GLFW") && call.name.equals("glfwWindowHint")) {
                    call.owner = WINDOW; call.name = "hint"; changed = true;
                } else if (game && call.owner.equals("org/lwjgl/glfw/GLFW") && call.name.equals("glfwSwapInterval")) {
                    call.owner = WINDOW; call.name = "swapInterval"; changed = true;
                } else if (game && call.owner.equals("com/mojang/blaze3d/systems/TimerQuery") && call.name.equals("getInstance") && call.desc.equals("()Ljava/util/Optional;")) {
                    call.owner = "java/util/Optional"; call.name = "empty"; changed = true;
                } else if (game && call.owner.equals("com/mojang/blaze3d/platform/GlDebug") && call.desc.equals("(IZ)V")) {
                    InsnList skip = new InsnList(); skip.add(new InsnNode(Opcodes.POP)); skip.add(new InsnNode(Opcodes.POP));
                    method.instructions.insertBefore(call, skip); method.instructions.remove(call); changed = true;
                }
            }
        }
        if (!changed) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static int hudScope(String owner,MethodNode method){
        if(owner.endsWith("/ItemInHandRenderer")&&(method.name.equals("renderHandsWithItems")||method.name.equals("m_109314_"))&&method.desc.equals("(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))return 0;
        if(owner.endsWith("/Gui")){
            if((method.name.equals("renderHotbar")||method.name.equals("m_280518_"))&&method.desc.equals("(FLnet/minecraft/client/gui/GuiGraphics;)V"))return 1;
            if((method.name.equals("renderPlayerHealth")||method.name.equals("m_280173_"))&&method.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;)V"))return 2;
        }
        if((method.name.equals("render")||method.name.equals("m_280421_"))&&method.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;F)V"))return 3;
        if(owner.endsWith("/ForgeGui"))return switch(method.name){case "renderHealth"->4;case "renderFood"->5;case "renderArmor"->6;case "renderAir"->7;default->-1;};return -1;
    }
    private static void instrumentHud(String owner,MethodNode method,int scope){
        String hook="github/com/gengyoubo/vulkanforge/client/NativeHudDiagnostics";
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){InsnList exit=new InsnList();exit.add(new LdcInsnNode(scope));exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,hook,"exit","(I)V",false));method.instructions.insertBefore(instruction,exit);}
        List<Object> locals=new ArrayList<>();if((method.access&Opcodes.ACC_STATIC)==0)locals.add(owner);
        for(Type type:Type.getArgumentTypes(method.desc))locals.add(switch(type.getSort()){case Type.LONG->Opcodes.LONG;case Type.DOUBLE->Opcodes.DOUBLE;case Type.FLOAT->Opcodes.FLOAT;case Type.OBJECT->type.getInternalName();case Type.ARRAY->type.getDescriptor();default->Opcodes.INTEGER;});
        InsnList enter=new InsnList();LabelNode original=new LabelNode();enter.add(new LdcInsnNode(scope));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,hook,"enter","(I)Z",false));enter.add(new JumpInsnNode(Opcodes.IFEQ,original));enter.add(new InsnNode(Opcodes.RETURN));enter.add(original);enter.add(new FrameNode(Opcodes.F_NEW,locals.size(),locals.toArray(),0,null));method.instructions.insert(enter);
    }
    private static boolean referencesFieldOwner(MethodNode method,String owner){for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode field&&field.owner.equals(owner))return true;return false;}
    private static int addProfileTimer(MethodNode method){
        int timer=method.maxLocals;method.maxLocals+=2;InsnList begin=new InsnList();begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","nanoTime","()J",false));begin.add(new VarInsnNode(Opcodes.LSTORE,timer));method.instructions.insert(begin);
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof FrameNode frame){int slots=0;for(Object local:frame.local)slots+=local.equals(Opcodes.LONG)||local.equals(Opcodes.DOUBLE)?2:1;while(slots++<timer)frame.local.add(Opcodes.TOP);frame.local.add(Opcodes.LONG);}return timer;
    }
    private static void trap(MethodNode method, String operation) {
        method.access &= ~Opcodes.ACC_NATIVE;
        method.instructions.clear(); method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.instructions.add(new LdcInsnNode("vulkanforge.forbiddenGlCalls")); method.instructions.add(new LdcInsnNode(operation));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/lang/IllegalStateException")); method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode("Native Vulkan forbids OpenGL: " + operation));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
    }
    private static void replaceCall(MethodNode method, MethodInsnNode call) {
        Type[] arguments = Type.getArgumentTypes(call.desc); int[] locals = new int[arguments.length];
        for (int i = 0; i < arguments.length; i++) { locals[i] = method.maxLocals; method.maxLocals += arguments[i].getSize(); }
        InsnList code = new InsnList();
        for (int i = arguments.length - 1; i >= 0; i--) code.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE),locals[i]));
        code.add(new LdcInsnNode(call.name)); code.add(new LdcInsnNode(arguments.length)); code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for (int i = 0; i < arguments.length; i++) {
            code.add(new InsnNode(Opcodes.DUP)); code.add(new LdcInsnNode(i)); code.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD),locals[i]));
            String boxed = box(arguments[i]);
            if (boxed != null) code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,boxed,"valueOf","(" + arguments[i].getDescriptor() + ")L" + boxed + ";",false));
            code.add(new InsnNode(Opcodes.AASTORE));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,STATE,"call","(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        Type result = Type.getReturnType(call.desc); String boxed = box(result);
        if (result.getSort() == Type.VOID) code.add(new InsnNode(Opcodes.POP));
        else if (boxed != null) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST,boxed));
            String operation = switch (result.getSort()) { case Type.BOOLEAN -> "booleanValue"; case Type.LONG -> "longValue"; case Type.FLOAT -> "floatValue"; case Type.DOUBLE -> "doubleValue"; default -> "intValue"; };
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,boxed,operation,"()" + result.getDescriptor(),false));
        } else code.add(new TypeInsnNode(Opcodes.CHECKCAST,result.getInternalName()));
        method.instructions.insertBefore(call, code); method.instructions.remove(call);
    }
    private static String box(Type type) {
        return switch (type.getSort()) { case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte"; case Type.SHORT -> "java/lang/Short";
            case Type.CHAR -> "java/lang/Character"; case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long"; case Type.FLOAT -> "java/lang/Float"; case Type.DOUBLE -> "java/lang/Double"; default -> null; };
    }
}
