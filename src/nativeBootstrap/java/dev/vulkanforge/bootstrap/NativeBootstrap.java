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
        boolean gl = name.startsWith("org/lwjgl/opengl/");
        boolean early = name.equals("net/minecraftforge/fml/loading/ImmediateWindowHandler");
        boolean glfw = name.equals("org/lwjgl/glfw/GLFW");
        boolean game = name.startsWith("com/mojang/blaze3d/") || name.startsWith("net/minecraft/client/")
                || name.startsWith("net/minecraftforge/client/") || name.startsWith("github/com/gengyoubo/vulkanforge/client/");
        if (!gl && !early && !glfw && !game) return null;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (gl && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
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
