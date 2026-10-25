package dev.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

final class ShaderCompiler {
    private ShaderCompiler() { }

    static long module(VulkanContext context, String resource, int kind) {
        String source;
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing shader: " + resource);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = 0, result = 0;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
            // Vulkan 1.3 SPIR-V is also valid on 1.4; these shaders need no 1.4-only instructions.
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_6);
            result = shaderc_compile_into_spv(compiler, source, kind, resource, "main", options);
            if (result == 0) throw new IllegalStateException("shaderc returned a null compilation result");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Shader compilation failed: " + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(bytes);
                LongBuffer handle = stack.mallocLong(1);
                VulkanContext.check(vkCreateShaderModule(context.device, info, null, handle), "vkCreateShaderModule");
                return handle.get(0);
            }
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
