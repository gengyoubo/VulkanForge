package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** Refuse entry into the hybrid executor; incomplete coverage must not silently become a hybrid launch. */
final class NativeModeAvailability {
    static void rejectIncompleteGameLaunch(Path report) {
        String reason="VK has a private Vulkan terrain/Swapchain backend, but it is not connected to Minecraft's game loop. Forge startup, CPU-origin resource capture and entity/GUI rendering still require migration away from OpenGL. Use GL_VK only if a hybrid game renderer is acceptable.";
        try{Files.createDirectories(report.getParent());Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"REJECTED_INCOMPLETE_COVERAGE\",\n  \"nativeWindowBackend\": \"IMPLEMENTED_NOT_CONNECTED\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"missing\": [\"FORGE_CAPTURE_WITHOUT_GL\", \"FORGE_EARLY_WINDOW_WITHOUT_GL\", \"MINECRAFT_RESOURCES_WITHOUT_GL\", \"ENTITY_GUI_COVERAGE\", \"NATIVE_GAME_RENDER_LOOP\"]\n}\n");}
        catch(java.io.IOException error){throw new IllegalStateException(reason+" Could not write compatibility report.",error);}
        throw new UnsupportedOperationException(reason+" Compatibility report: "+report.toAbsolutePath());
    }
}
