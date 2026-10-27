package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** A missing pre-Forge bootstrap must cause an actionable rejection, never a hybrid launch. */
public final class NativeModeGateSmoke {
    public static void main(String[] args) throws Exception {
        Path report=Path.of("build/reports/render-mode-config/native-game-rejected.json");
        try{NativeModeAvailability.rejectMissingBootstrap(report);throw new AssertionError("VK without its early bootstrap was accepted");}
        catch(UnsupportedOperationException expected){if(!expected.getMessage().contains("Compatibility report"))throw new AssertionError("No report location");}
        String data=Files.readString(report);if(!data.contains("REJECTED_MISSING_NATIVE_BOOTSTRAP")||!data.contains("\"hybridExecutorEntered\": false")||!data.contains("\"glFallbackAllowed\": false"))throw new AssertionError("Incorrect native gate report");
        if(!data.contains("\"nativeGameBackend\": \"CONNECTED\"")||!data.contains("EARLY_JVM_NATIVE_BOOTSTRAP"))throw new AssertionError("Missing bootstrap detail");
        System.out.println("NATIVE_MODE_GATE_SMOKE=OK (actionable bootstrap rejection, compatibility report, no hybrid fallback)");
    }
}
