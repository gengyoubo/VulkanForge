package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;

/** Missing full-game coverage must cause a visible rejection, never a silent hybrid launch. */
public final class NativeModeGateSmoke {
    public static void main(String[] args) throws Exception {
        Path report=Path.of("build/reports/render-mode-config/native-game-rejected.json");
        try{NativeModeAvailability.rejectIncompleteGameLaunch(report);throw new AssertionError("Incomplete VK game launch was accepted");}
        catch(UnsupportedOperationException expected){if(!expected.getMessage().contains("Compatibility report"))throw new AssertionError("No report location");}
        String data=Files.readString(report);if(!data.contains("REJECTED_INCOMPLETE_COVERAGE")||!data.contains("\"hybridExecutorEntered\": false")||!data.contains("\"glFallbackAllowed\": false"))throw new AssertionError("Incorrect native gate report");
        System.out.println("NATIVE_MODE_GATE_SMOKE=OK (explicit rejection, compatibility report, no hybrid fallback)");
    }
}
