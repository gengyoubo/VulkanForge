package github.com.gengyoubo.vulkanforge.lab;

import java.util.List;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

final class FrameGraphSmoke {
    static void run() {
        var image = new FrameGraph.Image("test", 1, VK_IMAGE_ASPECT_COLOR_BIT, FrameGraph.state(VK_IMAGE_LAYOUT_UNDEFINED, 0, 0));
        var write = FrameGraph.use(image, FrameGraph.state(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT));
        var read = FrameGraph.use(image, FrameGraph.state(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT));
        var graph = new FrameGraph(); graph.pass("upload", () -> {}, write); graph.pass("sample", () -> {}, read); graph.pass("sample again", () -> {}, read);
        if (!graph.schedule().equals(List.of("upload", "sample", "sample again")) || graph.barriers() != 2) throw new AssertionError("Graph hazard/readonly barrier elimination");
        var badRead = new FrameGraph(); badRead.pass("read", () -> {}, read); reject(badRead);
        var cycle = new FrameGraph(); cycle.pass("one", List.of("two"), () -> {}); cycle.pass("two", List.of("one"), () -> {}); reject(cycle);
        var missing = new FrameGraph(); missing.pass("one", List.of("missing"), () -> {}); reject(missing);
        System.out.println("FRAME_GRAPH_SMOKE=OK (hazards, read-only barrier elimination, uninitialized reads, cycles, unknown dependencies)");
    }
    private static void reject(FrameGraph graph) {
        try { graph.schedule(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid graph accepted");
    }
}
