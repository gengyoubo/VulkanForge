package dev.vulkanforge.bootstrap;

import java.lang.reflect.InvocationTargetException;
import org.objectweb.asm.*;

/** Tests the pre-Forge mode selector and real JVM verification/execution of the injected guards. */
public final class NativeBootstrapSmoke {
    public static void main(String[] ignored)throws Exception {
        String config="# comment\n[renderer]\nmode = 'VK' # saved selection\n[vulkan]\nversion=\"V1_2\"\n";
        require("VK:V1_2".equals(NativeBootstrap.nativeStartup(null,config)),"Saved VK/version ignored");
        require("VK:V1_3".equals(NativeBootstrap.nativeStartup("VK:V1_3",config)),"API override ignored");
        require("VK:V1_4".equals(NativeBootstrap.nativeStartup("VK:invalid","")),"Invalid API fallback");
        for(String mode:new String[]{"GL","OPENGL","GL_VK","VULKAN","HYBRID"})require(NativeBootstrap.nativeStartup(mode,config)==null,"Non-native mode entered native bootstrap: "+mode);
        require(NativeBootstrap.nativeStartup(null,"")==null,"Absent config enabled native mode");
        require(NativeBootstrap.nativeStartup(null,"[renderer]\nbackend=\"VULKAN\"\n")==null,"Legacy hybrid became native");
        require("VK:V1_4".equals(NativeBootstrap.nativeStartup(null,"[renderer]\nmode=\"VK_NATIVE\"\n")),"Native alias ignored");
        String previous=System.getProperty("vulkanforge.forbiddenGlCalls");
        try {
            Class<?> gl=define("org/lwjgl/opengl/NativeGuardProbe",glProbe());
            try{gl.getMethod("glGetError").invoke(null);throw new AssertionError("Original GL entry point escaped");}
            catch(InvocationTargetException expected){require(expected.getCause() instanceof IllegalStateException,"Guard did not replace the native entry point");}
            require(System.getProperty("vulkanforge.forbiddenGlCalls").contains("glGetError"),"GL attempt not recorded");
            Class<?> glfw=define("org/lwjgl/glfw/GLFW",glfwProbe());glfw.getField("api").setInt(null,196609);
            long api=(Long)glfw.getMethod("glfwCreateWindow").invoke(null);require(api==0,"Window creation was allowed to retain OPENGL_API");
            try{glfw.getMethod("glfwMakeContextCurrent",long.class).invoke(null,7L);throw new AssertionError("Context binding escaped");}
            catch(InvocationTargetException expected){require(expected.getCause() instanceof IllegalStateException,"Context guard absent");}
        } finally {if(previous==null)System.clearProperty("vulkanforge.forbiddenGlCalls");else System.setProperty("vulkanforge.forbiddenGlCalls",previous);}
        System.out.println("NATIVE_BOOTSTRAP_SMOKE=OK (saved config/API/legacy aliases, inactive GL modes, verified GL traps and forced NO_API window creation)");
    }
    private static Class<?> define(String name,byte[] original){byte[] transformed=NativeBootstrap.rewrite(name,original);return new ClassLoader(ClassLoader.getPlatformClassLoader()){Class<?> load(){return defineClass(name.replace('/','.'),transformed,0,transformed.length);}}.load();}
    private static byte[] glProbe(){ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"org/lwjgl/opengl/NativeGuardProbe",null,"java/lang/Object",null);writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE,"glGetError","()I",null,null).visitEnd();writer.visitEnd();return writer.toByteArray();}
    private static byte[] glfwProbe(){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"org/lwjgl/glfw/GLFW",null,"java/lang/Object",null);writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"api","I",null,null).visitEnd();
        var hint=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"glfwWindowHint","(II)V",null,null);hint.visitCode();hint.visitVarInsn(Opcodes.ILOAD,1);hint.visitFieldInsn(Opcodes.PUTSTATIC,"org/lwjgl/glfw/GLFW","api","I");hint.visitInsn(Opcodes.RETURN);hint.visitMaxs(0,0);hint.visitEnd();
        var create=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"glfwCreateWindow","()J",null,null);create.visitCode();create.visitFieldInsn(Opcodes.GETSTATIC,"org/lwjgl/glfw/GLFW","api","I");create.visitInsn(Opcodes.I2L);create.visitInsn(Opcodes.LRETURN);create.visitMaxs(0,0);create.visitEnd();
        var bind=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"glfwMakeContextCurrent","(J)V",null,null);bind.visitCode();bind.visitInsn(Opcodes.RETURN);bind.visitMaxs(0,0);bind.visitEnd();writer.visitEnd();return writer.toByteArray();}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
