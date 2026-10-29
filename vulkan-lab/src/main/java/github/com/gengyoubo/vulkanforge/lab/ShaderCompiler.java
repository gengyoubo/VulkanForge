package github.com.gengyoubo.vulkanforge.lab;

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

    /** Textual {@code #include "file"} from the same resource folder; shaderc's include callbacks are not wired. */
    private static String expandIncludes(String source, String resource) {
        String folder = resource.substring(0, resource.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#extension GL_GOOGLE_include_directive")) continue;
            if (!trimmed.startsWith("#include \"")) { out.append(line).append('\n'); continue; }
            String name = folder + trimmed.substring(10, trimmed.lastIndexOf('"'));
            try (InputStream in = ShaderCompiler.class.getResourceAsStream(name)) {
                if (in == null) throw new IOException("Missing shader include: " + name);
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        return out.toString();
    }

    static long module(VulkanContext context, String resource, int kind) {
        String source;
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing shader: " + resource);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
        source = expandIncludes(source, resource);
        return moduleSource(context,source,resource,kind);
    }
    static long moduleSource(VulkanContext context,String source,String resource,int kind){
        return moduleSource(context,source,resource,kind,false,null);
    }
    static long moduleSource(VulkanContext context,String source,String resource,int kind,boolean optimized,java.util.function.Consumer<ByteBuffer> inspect){
        java.nio.file.Path cached=null;
        try{
            String key="native-glsl-v3/"+context.apiVersion+"/"+kind+"/"+optimized+"/"+source;
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            cached=java.nio.file.Path.of(System.getProperty("vulkanforge.shaderCacheDirectory","cache/vulkanforge/spirv")).resolve(hash+".spv");
            if(java.nio.file.Files.isRegularFile(cached)&&java.nio.file.Files.size(cached)<=32*1024*1024){byte[] raw=java.nio.file.Files.readAllBytes(cached);if(raw.length>=20&&raw.length%4==0){ByteBuffer bytes=org.lwjgl.system.MemoryUtil.memAlloc(raw.length);try{bytes.put(raw).flip();if(bytes.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)==0x07230203)return createModule(context,bytes,inspect);}finally{org.lwjgl.system.MemoryUtil.memFree(bytes);}}}
        }catch(IOException|java.security.NoSuchAlgorithmException ignored){}
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = 0, result = 0;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
            // Vulkan 1.2 accepts SPIR-V 1.5; the 1.3/1.4 paths can use SPIR-V 1.6.
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, context.apiVersion < org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3 ? shaderc_env_version_vulkan_1_2 : shaderc_env_version_vulkan_1_3);
            shaderc_compile_options_set_target_spirv(options, context.apiVersion < org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3 ? shaderc_spirv_version_1_5 : shaderc_spirv_version_1_6);
            if (optimized) shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            result = shaderc_compile_into_spv(compiler, source, kind, resource, "main", options);
            if (result == 0) throw new IllegalStateException("shaderc returned a null compilation result");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                String diagnostic=System.getProperty("vulkanforge.shaderFailureDirectory");if(diagnostic!=null)try{var folder=java.nio.file.Path.of(diagnostic);java.nio.file.Files.createDirectories(folder);java.nio.file.Files.writeString(folder.resolve(resource.replace('/','_')+".glsl"),source,StandardCharsets.UTF_8);}catch(IOException ignored){}
                throw new IllegalStateException("Shader compilation failed: " + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null) throw new IllegalStateException("shaderc returned no SPIR-V");
            if(cached!=null)try{java.nio.file.Files.createDirectories(cached.getParent());byte[] raw=new byte[bytes.remaining()];bytes.duplicate().get(raw);var temporary=java.nio.file.Files.createTempFile(cached.getParent(),"shader-",".tmp");try{java.nio.file.Files.write(temporary,raw);java.nio.file.Files.move(temporary,cached,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{java.nio.file.Files.deleteIfExists(temporary);}}catch(IOException ignored){}
            return createModule(context,bytes,inspect);
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
    private static long createModule(VulkanContext context,ByteBuffer bytes,java.util.function.Consumer<ByteBuffer> inspect){
        if(inspect!=null)inspect.accept(bytes.asReadOnlyBuffer());
        try(MemoryStack stack=MemoryStack.stackPush()){
            var info=VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(bytes);var handle=stack.mallocLong(1);
            VulkanContext.check(vkCreateShaderModule(context.device,info,null,handle),"vkCreateShaderModule");return handle.get(0);
        }
    }
}
