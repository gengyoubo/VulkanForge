package github.com.gengyoubo.vulkanforge.lab;

import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Hard runtime test boundary: offscreen native code cannot even load GL, GLFW or the interop implementation. */
public final class VkOnlyLauncher {
    public static void main(String[] args) throws Exception {
        AtomicInteger attempted=new AtomicInteger();var urls=java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).map(p->{try{return p.toUri().toURL();}catch(MalformedURLException e){throw new IllegalArgumentException(e);}}).toArray(URL[]::new);
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        try(URLClassLoader isolated=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader()){
            protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("org.lwjgl.opengl.")||name.startsWith("org.lwjgl.glfw.")||name.equals("github.com.gengyoubo.vulkanforge.interop.GlInterop")){
                    attempted.incrementAndGet();throw new ClassNotFoundException("VK native forbids GL/GLFW/interop: "+name);
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Thread.currentThread().setContextClassLoader(isolated);
            try{Class.forName(args.length==0?"github.com.gengyoubo.vulkanforge.lab.VkNativeOffscreenBenchmark":args[0],true,isolated).getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
            catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof Error e)throw e;if(error.getCause() instanceof Exception e)throw e;throw error;}
            if(attempted.get()!=0)throw new AssertionError("Native code attempted "+attempted.get()+" forbidden class loads");
            System.out.println("VK_ONLY_CLASSLOADER=PASS GL_GLFW_INTEROP_LOAD_ATTEMPTS=0");
        }finally{Thread.currentThread().setContextClassLoader(previous);}
    }
}
