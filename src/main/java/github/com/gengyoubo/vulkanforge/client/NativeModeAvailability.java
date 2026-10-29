package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** Native mode must be selected before Forge can create its early OpenGL window. */
final class NativeModeAvailability {
    static void bootstrapOrReject(Path gameDirectory, String startup) {
        Path report = gameDirectory.resolve("logs/vulkanforge/vk-native-compatibility.json");
        if (!Boolean.getBoolean(NativeBootstrapLauncher.RELAUNCHED) && NativeBootstrapLauncher.available()) {
            try {
                System.out.println("VulkanForge: enabling the bundled VK bootstrap in a fresh JVM.");
                NativeBootstrapLauncher.prepareRestart(gameDirectory, startup);
                // No Minecraft window or world exists yet. The helper waits for this JVM to exit.
                System.exit(0);
            } catch (Exception error) {
                // Launcher commands can contain credentials; expose only the failure type.
                System.err.println("VulkanForge native relaunch could not be prepared (" + error.getClass().getSimpleName() + ")");
            }
        }
        rejectMissingBootstrap(report);
    }
    static void markActive(Path report) throws java.io.IOException {
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"NATIVE_ACTIVE\",\n  \"nativeGameBackend\": \"CONNECTED\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"openGlContexts\": 0,\n  \"missing\": []\n}\n");
    }
    static void rejectMissingBootstrap(Path report) {
        String reason="VK requires its bootstrap before Forge starts. Automatic relaunch with the bundled bootstrap was unavailable or did not activate native mode. See logs/vulkanforge/restart.log. As a manual alternative, add -javaagent:<absolute path to vulkanforge-native-bootstrap.jar>=<absolute game directory> to your launcher's JVM arguments; putting the agent JAR in mods alone does not activate it.";
        try{Files.createDirectories(report.toAbsolutePath().getParent());Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"REJECTED_MISSING_NATIVE_BOOTSTRAP\",\n  \"nativeGameBackend\": \"CONNECTED\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"missing\": [\"EARLY_JVM_NATIVE_BOOTSTRAP\"]\n}\n");}
        catch(java.io.IOException error){throw new IllegalStateException(reason+" Could not write compatibility report.",error);}
        throw new UnsupportedOperationException(reason+" Compatibility report: "+report.toAbsolutePath());
    }
}
