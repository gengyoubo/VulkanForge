package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Small per-submit render graph: explicit image usages produce hazard edges, a stable schedule and synchronization2 barriers. */
final class FrameGraph {
    record State(int layout, long stage, long access, int queue) { }
    record Image(String name, long handle, int aspect, State initial) { }
    record Use(Image image, State state) { }
    @FunctionalInterface interface Commands { void record(); }
    private record Pass(String name, List<Use> uses, List<String> after, Commands commands) { }
    private record Step(Pass pass, List<Transition> transitions) { }
    record Transition(Image image, State from, State to) { }
    private final List<Pass> passes = new ArrayList<>();
    private List<Step> compiled;
    private static final long WRITES = VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
            | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
    static State state(int layout, long stage, long access) { return new State(layout, stage, access, VK_QUEUE_FAMILY_IGNORED); }
    static Use use(Image image, State state) { return new Use(image, state); }
    void pass(String name, Commands commands, Use... uses) { pass(name, List.of(), commands, uses); }
    void pass(String name, List<String> after, Commands commands, Use... uses) {
        if (passes.stream().anyMatch(p -> p.name.equals(name))) throw new IllegalArgumentException("Duplicate graph pass " + name);
        Set<Image> seen = new HashSet<>();
        for (Use use : uses) if (!seen.add(use.image)) throw new IllegalArgumentException("Declare a combined usage for " + use.image.name);
        passes.add(new Pass(name, List.of(uses), List.copyOf(after), commands));
        compiled = null;
    }

    /** Dependencies combine explicit pass edges with read/write hazards in declaration order. */
    private List<Step> compile() {
        if (compiled != null) return compiled;
        Map<String, Pass> names = new LinkedHashMap<>(); passes.forEach(p -> names.put(p.name, p));
        Map<Pass, Set<Pass>> dependencies = new LinkedHashMap<>();
        Map<Image, State> previousStates = new HashMap<>();
        Map<Image, Pass> writer = new HashMap<>(); Map<Image, Set<Pass>> readers = new HashMap<>();
        for (Pass pass : passes) {
            Set<Pass> deps = new HashSet<>(); dependencies.put(pass, deps);
            for (String name : pass.after) {
                Pass before = names.get(name); if (before == null) throw new IllegalArgumentException("Unknown graph dependency " + name); deps.add(before);
            }
            for (Use use : pass.uses) {
                if (writer.containsKey(use.image)) deps.add(writer.get(use.image));
                Set<Pass> previousReaders = readers.computeIfAbsent(use.image, key -> new HashSet<>());
                State previous = previousStates.getOrDefault(use.image, use.image.initial); previousStates.put(use.image, use.state);
                if ((use.state.access & WRITES) != 0 || use.state.queue != previous.queue || use.state.layout != previous.layout) {
                    deps.addAll(previousReaders); previousReaders.clear(); writer.put(use.image, pass);
                } else previousReaders.add(pass);
            }
        }
        List<Pass> ordered = new ArrayList<>(); Set<Pass> done = new HashSet<>();
        while (ordered.size() < passes.size()) {
            Pass next = passes.stream().filter(p -> !done.contains(p) && done.containsAll(dependencies.get(p))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Render graph contains a dependency cycle"));
            ordered.add(next); done.add(next);
        }
        Map<Image, State> states = new HashMap<>(); List<Step> result = new ArrayList<>();
        for (Pass pass : ordered) {
            List<Transition> transitions = new ArrayList<>();
            for (Use use : pass.uses) {
                State old = states.getOrDefault(use.image, use.image.initial), next = use.state;
                if (old.layout == VK_IMAGE_LAYOUT_UNDEFINED && (next.access & WRITES) == 0) throw new IllegalArgumentException("Read before initialization: " + use.image.name);
                if (old.layout != next.layout || old.queue != next.queue || ((old.access | next.access) & WRITES) != 0) transitions.add(new Transition(use.image, old, next));
                else next = new State(next.layout, old.stage | next.stage, old.access | next.access, next.queue);
                states.put(use.image, next);
            }
            result.add(new Step(pass, transitions));
        }
        return compiled = List.copyOf(result);
    }
    List<String> schedule() { return compile().stream().map(s -> s.pass.name).toList(); }
    int barriers() { return compile().stream().mapToInt(s -> s.transitions.size()).sum(); }
    int execute(VkCommandBuffer command, MemoryStack stack) {
        int count = 0;
        for (Step step : compile()) {
            if (!step.transitions.isEmpty()) {
                var barriers = VkImageMemoryBarrier2.calloc(step.transitions.size(), stack);
                for (int i = 0; i < step.transitions.size(); i++) {
                    Transition t = step.transitions.get(i); boolean ownership = t.from.queue != t.to.queue;
                    barriers.get(i).sType$Default().srcStageMask(t.from.stage).srcAccessMask(t.from.access)
                            .dstStageMask(t.to.stage).dstAccessMask(t.to.access).oldLayout(t.from.layout).newLayout(t.to.layout)
                            .srcQueueFamilyIndex(ownership ? t.from.queue : VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(ownership ? t.to.queue : VK_QUEUE_FAMILY_IGNORED)
                            .image(t.image.handle).subresourceRange(r -> r.aspectMask(t.image.aspect).levelCount(1).layerCount(1));
                }
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers)); count += step.transitions.size();
            }
            step.pass.commands.record();
        }
        return count;
    }
}
