package github.com.gengyoubo.vulkanforge.lab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Stream;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Build step for the OpenGL 4.6 backend: GLSL -> OpenGL-flavoured SPIR-V (ARB_gl_spirv), one {@code <name>.spv} per stage.
 * The mod ships the GLSL sources too and falls back to them when a driver rejects the binary.
 */
public final class GlSpirvCompiler {
    private GlSpirvCompiler() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: GlSpirvCompiler <glsl dir> <output dir>");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        int compiled = 0;
        try (Stream<Path> files = Files.list(input)) {
            for (Path source : (Iterable<Path>) files.sorted()::iterator) {
                String name = source.getFileName().toString();
                int kind = name.endsWith(".vert") ? shaderc_glsl_vertex_shader : name.endsWith(".frag") ? shaderc_glsl_fragment_shader
                        : name.endsWith(".comp") ? shaderc_glsl_compute_shader : -1;
                if (kind < 0) continue;
                Files.write(output.resolve(name + ".spv"), compile(Files.readString(source, StandardCharsets.UTF_8), name, kind));
                compiled++;
            }
        }
        System.out.println("GL_SPIRV_SHADERS=" + compiled);
    }

    static byte[] compile(String source, String name, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = 0, result = 0;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
            // shaderc's newest OpenGL environment is 4.5; GL 4.6 consumes it through glSpecializeShader.
            shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
            result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            if (result == 0) throw new IllegalStateException("shaderc returned a null compilation result");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("GL SPIR-V compilation failed for " + name + ": " + shaderc_result_get_error_message(result));
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V for " + name);
            byte[] copy = new byte[bytes.remaining()]; bytes.get(copy);
            return copy;
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
