import java.nio.file.Path;
import java.time.Instant;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Bounded-memory inclusive JFR counts; deliberately never prints JVM arguments or paths. */
public final class Ae2RecordingAnalysis {
    static boolean contains(RecordedStackTrace stack, String type, String method) {
        if (stack == null) return false;
        for (RecordedFrame frame : stack.getFrames()) {
            var m = frame.getMethod();
            if (m.getType().getName().equals(type) && (method == null || m.getName().equals(method))) return true;
        }
        return false;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("recording startMillis endMillis");
        Instant start = Instant.ofEpochMilli(Long.parseLong(args[1])), end = Instant.ofEpochMilli(Long.parseLong(args[2]));
        long cpu = 0, target = 0, controller = 0, energy = 0, allocation = 0, allocationTarget = 0, truncated = 0;
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent(); String kind = e.getEventType().getName();
                if (!kind.equals("jdk.ExecutionSample") && !kind.equals("jdk.ObjectAllocationSample")) continue;
                if (e.getStartTime().isBefore(start) || !e.getStartTime().isBefore(end)) continue;
                RecordedThread t = e.getThread(kind.equals("jdk.ExecutionSample") ? "sampledThread" : "eventThread");
                if (t == null || !"Server thread".equals(t.getJavaName())) continue;
                RecordedStackTrace stack = e.getStackTrace(); if (stack == null || stack.getFrames().isEmpty()) continue;
                boolean hit = contains(stack, "moakiee.support.EnergyCardRuntime", "getInstalledEnergyCards");
                if (kind.equals("jdk.ExecutionSample")) {
                    cpu++; if (stack.isTruncated()) truncated++;
                    if (hit) {
                        target++;
                        if (contains(stack, "appeng.blockentity.networking.ControllerBlockEntity", null)) controller++;
                        if (contains(stack, "mekanism.common.content.network.EnergyNetwork", "tickEmit")) energy++;
                    }
                } else { allocation++; if (hit) allocationTarget++; }
            }
        }
        System.out.printf("AE2_RECORDING cpu_samples=%d target_samples=%d controller_frame_samples=%d mekanism_energy_samples=%d allocation_samples=%d target_allocation_samples=%d truncated_cpu=%d%n",
              cpu, target, controller, energy, allocation, allocationTarget, truncated);
    }
}
