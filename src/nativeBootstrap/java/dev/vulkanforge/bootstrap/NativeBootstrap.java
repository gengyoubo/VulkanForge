package dev.vulkanforge.bootstrap;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Opt-in JVM bootstrap: disable Forge's GL early window and fail every real GL entry point. */
public final class NativeBootstrap {
    private static final String STATE = "github/com/gengyoubo/vulkanforge/client/NativeDeviceState";
    private static final String WINDOW = "github/com/gengyoubo/vulkanforge/client/NativeGameWindow";
    public static void premain(String arguments, Instrumentation instrumentation) {
        String mode = System.getProperty("vulkanforge.startup", "VK:V1_4");
        if (!mode.startsWith("VK:")) throw new IllegalArgumentException("Native bootstrap requires an explicit VK startup mode");
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
