package github.com.gengyoubo.vulkanforge.client.shader;

import github.com.gengyoubo.vulkanforge.client.NativeGameWindow;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL41.*;

/**
 * GL program binaries of fullscreen pack passes, keyed by driver identity and final GLSL. A driver update or a rejected
 * binary falls back to a normal compile. Native VK mode never uses this: its SPIR-V and pipeline caches live in the backend.
 */
final class ShaderPackProgramCache {
    private static final int MAX_BYTES=64*1024*1024;
    private static Boolean supported;
    private static String driver;
    static long hits,misses;
    private ShaderPackProgramCache() { }
    private static boolean supported(){
        if(supported==null){
            boolean available=!NativeGameWindow.enabled()&&!Boolean.getBoolean("vulkanforge.disableProgramCache");
            if(available){var caps=GL.getCapabilities();available=(caps.OpenGL41||caps.GL_ARB_get_program_binary)&&glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS)>0;}
            if(available)driver=glGetString(GL_VENDOR)+"|"+glGetString(GL_RENDERER)+"|"+glGetString(GL_VERSION);
            supported=available;
        }
        return supported;
    }
    /** Null when program binaries are unavailable. */
    static String key(String vertex,String fragment,Map<String,Integer> attributes){
        if(!supported())return null;
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(String part:new String[]{"pack-program-v1",driver,vertex,fragment,new TreeMap<>(attributes).toString()}){digest.update(part.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException error){return null;}
    }
    private static Path file(String key){return Minecraft.getInstance().gameDirectory.toPath().resolve("cache/vulkanforge/gl-programs").resolve(key+".bin");}
    /** A linked program, or 0 on a miss. */
    static int load(String key){
        if(key==null)return 0;
        byte[] raw;
        try{Path file=file(key);if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES){misses++;return 0;}raw=Files.readAllBytes(file);}catch(IOException error){misses++;return 0;}
        if(raw.length<5){misses++;return 0;}
        int format=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0);ByteBuffer binary=MemoryUtil.memAlloc(raw.length-4);int program=glCreateProgram();
        try{
            binary.put(raw,4,raw.length-4).flip();glProgramBinary(program,format,binary);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_TRUE){hits++;int loaded=program;program=0;return loaded;}
        }finally{MemoryUtil.memFree(binary);if(program!=0)glDeleteProgram(program);}
        misses++;try{Files.deleteIfExists(file(key));}catch(IOException ignored){}
        return 0;
    }
    /** Call before glLinkProgram so the driver keeps a retrievable binary. */
    static void retrievable(String key,int program){if(key!=null)glProgramParameteri(program,GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL_TRUE);}
    static void store(String key,int program){
        if(key==null)return;
        int length=glGetProgrami(program,GL_PROGRAM_BINARY_LENGTH);if(length<=0||length>MAX_BYTES)return;
        ByteBuffer binary=MemoryUtil.memAlloc(length);
        try(MemoryStack stack=MemoryStack.stackPush()){
            var written=stack.mallocInt(1);var format=stack.mallocInt(1);glGetProgramBinary(program,written,format,binary);
            byte[] raw=new byte[4+written.get(0)];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(0,format.get(0));binary.get(0,raw,4,written.get(0));
            Path file=file(key);Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"program-",".tmp");
            try{Files.write(temporary,raw);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        }catch(IOException ignored){}finally{MemoryUtil.memFree(binary);}
    }
}
