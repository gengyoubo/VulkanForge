package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.WindowsLibrary;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * Windows global timer resolution (ntdll NtQueryTimerResolution / NtSetTimerResolution, 100 ns units). The present paths pace at
 * suspiciously exact 1.000 ms (GL) and 4.00 ms (Vulkan) steps; -Dbench.timerResolutionUs=N in modes B/C requests a finer timer to
 * test whether the drivers' waits are timer-quantized. The request lasts until the process exits. main() only prints the state.
 */
final class TimerResolution {
    private static final WindowsLibrary NTDLL = new WindowsLibrary("ntdll");

    private TimerResolution() { }

    /** {coarsest, finest, current} in milliseconds. */
    static double[] query() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var values = stack.callocInt(3);
            int status = JNI.callPPPI(memAddress(values), memAddress(values) + 4, memAddress(values) + 8, NTDLL.getFunctionAddress("NtQueryTimerResolution"));
            if (status != 0) throw new IllegalStateException("NtQueryTimerResolution status=0x" + Integer.toHexString(status));
            return new double[]{values.get(0) / 1e4, values.get(1) / 1e4, values.get(2) / 1e4};
        }
    }

    /** Requests the given resolution for this process; returns the resulting current resolution in milliseconds. */
    static double request(int micros) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var current = stack.callocInt(1);
            int status = JNI.callPI(micros * 10, 1, memAddress(current), NTDLL.getFunctionAddress("NtSetTimerResolution"));
            if (status != 0) throw new IllegalStateException("NtSetTimerResolution status=0x" + Integer.toHexString(status));
            return current.get(0) / 1e4;
        }
    }

    /** Applies -Dbench.timerResolutionUs if given and prints the timer state as TIMER_RESOLUTION. */
    static void configure() {
        Integer micros = Integer.getInteger("bench.timerResolutionUs");
        double[] before = query();
        String requested = micros == null ? "" : String.format(" requested=%.3fms -> current=%.3fms", micros / 1e3, request(micros));
        System.out.printf("TIMER_RESOLUTION coarsest=%.3fms finest=%.3fms current=%.3fms%s%n", before[0], before[1], before[2], requested);
    }

    public static void main(String[] args) { configure(); }
}
