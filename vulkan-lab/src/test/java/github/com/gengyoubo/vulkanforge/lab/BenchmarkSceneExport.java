package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.io.*;
import java.nio.file.*;

/** The GL benchmark consumes the exact scene generator used by the Vulkan benchmark. */
public final class BenchmarkSceneExport {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]); Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            String scene = BenchmarkScene.load(command -> {
                try { WorldProtocol.write(out, command); } catch (IOException error) { throw new UncheckedIOException(error); }
            }, Integer.getInteger("bench.width", 1920), Integer.getInteger("bench.height", 1080));
            Files.writeString(file.resolveSibling("scene.txt"), scene + "\n");
            System.out.println(scene);
        }
    }
}
