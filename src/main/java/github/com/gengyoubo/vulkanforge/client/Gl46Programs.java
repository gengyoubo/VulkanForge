package github.com.gengyoubo.vulkanforge.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL41C.glShaderBinary;
import static org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER;
import static org.lwjgl.opengl.GL46C.*;

/**
 * GL 4.6 programs from build-time SPIR-V (glShaderBinary + glSpecializeShader); a driver that rejects the binary, or
 * -Dvulkanforge.gl46.glsl=true, gets GLSL source compiled at runtime instead. GL 4.3-4.5 use GLSL 4.30
 * and a divisor-one integer vertex attribute for baseInstance, requiring no draw-parameters extension.
 */
final class Gl46Programs {
    private static final String ROOT = "/assets/vulkanforge/shaders/gl46/";
    private static final java.util.Map<String, byte[]> RESOURCES = new java.util.HashMap<>();
    private static long resourceReads, resourceHits;
    private Gl46Programs() { }

    record Program(int id, boolean spirv) { }

    static Program load(String... stages) {
        long start = System.nanoTime();
        try { return compile(stages); }
        finally { Gl46Context.report("PROGRAM_LOAD " + String.join("+", stages) + " cpuUs=" + (System.nanoTime()-start)/1e3
                + " resourceReads=" + resourceReads + " resourceHits=" + resourceHits); }
    }
    private static Program compile(String[] stages) {
        if (GlCompat.core46() && !Boolean.getBoolean("vulkanforge.gl46.glsl")) {
            try { return new Program(link(stages, true), true); }
            catch (RuntimeException error) { Gl46Context.report("SPIRV_REJECTED " + String.join("+", stages) + ": " + error.getMessage()); }
        }
        return new Program(link(stages, false), false);
    }

    private static int link(String[] stages, boolean spirv) {
        int program = glCreateProgram();
        int[] shaders = new int[stages.length];
        try {
            for (int i = 0; i < stages.length; i++) {
                shaders[i] = spirv ? binary(stages[i]) : source(stages[i]);
                glAttachShader(program, shaders[i]);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException("Link failed for " + String.join("+", stages) + ": " + glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException error) {
            glDeleteProgram(program); program = 0;
            throw error;
        } finally {
            for (int shader : shaders) if (shader != 0) { if (program != 0) glDetachShader(program, shader); glDeleteShader(shader); }
        }
    }

    private static int binary(String name) {
        byte[] code = resource(name + ".spv");
        int shader = glCreateShader(type(name));
        ByteBuffer bytes = MemoryUtil.memAlloc(code.length);
        try {
            bytes.put(code).flip();
            glShaderBinary(new int[]{shader}, GL_SHADER_BINARY_FORMAT_SPIR_V, bytes);
            glSpecializeShader(shader, "main", new int[0], new int[0]);
            check(shader, name + ".spv");
            return shader;
        } catch (RuntimeException error) { glDeleteShader(shader); throw error; }
        finally { MemoryUtil.memFree(bytes); }
    }

    private static int source(String name) {
        int shader = glCreateShader(type(name));
        try {
            String source = new String(resource(name), StandardCharsets.UTF_8);
            if (!GlCompat.core46()) {
                source = source.replace("#version 460 core", "#version 430 core");
                if (name.endsWith(".vert")) {
                    source = source.replace("gl_BaseInstance + gl_InstanceID", "compatInstance").replace("gl_BaseInstance", "compatInstance");
                    source = source.replace("void main()", "layout(location = 15) in int compatInstance;\nvoid main()");
                }
            }
            glShaderSource(shader, source);
            glCompileShader(shader);
            check(shader, name);
            return shader;
        } catch (RuntimeException error) { glDeleteShader(shader); throw error; }
    }

    private static void check(int shader, String name) {
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(name + ": " + glGetShaderInfoLog(shader));
    }

    private static int type(String name) {
        return name.endsWith(".vert") ? GL_VERTEX_SHADER : name.endsWith(".frag") ? GL_FRAGMENT_SHADER : GL_COMPUTE_SHADER;
    }

    private static byte[] resource(String name) {
        byte[] cached = RESOURCES.get(name);
        if (cached != null) { resourceHits++; return cached; }
        try (InputStream in = Gl46Programs.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException("Missing GL 4.6 shader resource " + ROOT + name);
            byte[] bytes = in.readAllBytes(); RESOURCES.put(name, bytes); resourceReads++; return bytes;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
