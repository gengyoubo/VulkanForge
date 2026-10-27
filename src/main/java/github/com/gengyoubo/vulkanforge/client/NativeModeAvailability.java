package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** Native mode must be selected before Forge can create its early OpenGL window. */
final class NativeModeAvailability {
    static void markActive(Path report) throws java.io.IOException {
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"NATIVE_ACTIVE\",\n  \"nativeGameBackend\": \"CONNECTED\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"openGlContexts\": 0,\n  \"missing\": []\n}\n");
    }
    static void rejectMissingBootstrap(Path report) {
        String reason="VK game rendering requires vulkanforge-native-bootstrap.jar as a JVM javaagent before Forge starts. Gradle runClient installs it automatically; refresh/regenerate IDE run configurations or add -javaagent:<absolute path to vulkanforge-native-bootstrap.jar> in your launcher's JVM arguments.";
        try{Files.createDirectories(report.toAbsolutePath().getParent());Files.writeString(report,"{\n  \"mode\": \"VK\",\n  \"gameLaunch\": \"REJECTED_MISSING_NATIVE_BOOTSTRAP\",\n  \"nativeGameBackend\": \"CONNECTED\",\n  \"hybridExecutorEntered\": false,\n  \"glFallbackAllowed\": false,\n  \"missing\": [\"EARLY_JVM_NATIVE_BOOTSTRAP\"]\n}\n");}
        catch(java.io.IOException error){throw new IllegalStateException(reason+" Could not write compatibility report.",error);}
        throw new UnsupportedOperationException(reason+" Compatibility report: "+report.toAbsolutePath());
    }
}
