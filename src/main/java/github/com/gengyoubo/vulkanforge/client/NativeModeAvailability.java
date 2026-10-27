package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** Refuse entry into the hybrid executor; incomplete coverage must not silently become a hybrid launch. */
final class NativeModeAvailability {
    static void rejectIncompleteGameLaunch(Path report) {
        String reason="VK mode currently supports the native offscreen development path. Complete Forge draw capture, entity/GUI coverage and native game presentation are not ready. Use GL_VK for the existing hybrid game renderer.";
        try{Files.createDirectories(report.getParent());Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"REJECTED_INCOMPLETE_COVERAGE\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"missing\": [\"FORGE_CAPTURE_WITHOUT_GL\", \"FORGE_EARLY_WINDOW_WITHOUT_GL\", \"ENTITY_GUI_COVERAGE\", \"NATIVE_GAME_SWAPCHAIN\"]\n}\n");}
        catch(java.io.IOException error){throw new IllegalStateException(reason+" Could not write compatibility report.",error);}
        throw new UnsupportedOperationException(reason+" Compatibility report: "+report.toAbsolutePath());
    }
}
