package github.com.gengyoubo.vulkanforge.client;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Restart coordinator; the extracted helper entry point uses only JDK classes and outlives Forge. */
public final class GameRestart {
    private GameRestart() { }

    /** Called off the render thread. Returns only after the helper has accepted the launch command. */
    static void prepare(Path log) throws Exception {
        prepare(log, restartCommand());
    }

    static void prepare(Path log, List<String> command) throws Exception {
        Path directory = Files.createTempDirectory("vulkanforge-restart-");
        Path helper = directory.resolve(GameRestart.class.getName().replace('.', '/') + ".class");
        Process process = null;
        boolean ready = false;
        try {
            Files.createDirectories(helper.getParent());
            try (InputStream in = GameRestart.class.getResourceAsStream("GameRestart.class")) {
                if (in == null) throw new IOException("Restart helper is missing");
                Files.copy(in, helper);
            }
            Files.createDirectories(log.toAbsolutePath().getParent());
            ProcessBuilder builder = new ProcessBuilder(command.get(0), "-cp", directory.toString(),
                    GameRestart.class.getName(), Long.toString(ProcessHandle.current().pid()),
                    Path.of("").toAbsolutePath().toString(), log.toAbsolutePath().toString());
            // These options are already expanded in RuntimeMXBean; do not apply them twice on restart.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
            process = builder.start();
            // Send potentially private launcher arguments through a pipe, never through a file or log.
            try (DataOutputStream out = new DataOutputStream(process.getOutputStream())) {
                out.writeInt(command.size());
                for (String argument : command) {
                    byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            InputStream input = process.getInputStream();
            while (input.available() == 0 && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            if (input.available() == 0 || input.read() != 1) throw new IOException("Restart helper did not become ready");
            ready = true;
        } finally {
            if (!ready) {
                if (process != null) process.destroyForcibly().waitFor();
                deleteHelper(directory);
            }
        }
    }

    static List<String> restartCommand() throws IOException {
        return restartCommand(List.of());
    }

    static List<String> restartCommand(List<String> bootstrapOptions) throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElseThrow(() -> new IOException("Java executable is unavailable"));
        List<String> arguments;
        if (info.arguments().isPresent()) arguments = Arrays.asList(info.arguments().get());
        else if (System.getProperty("os.name").startsWith("Windows")) {
            // Windows JDK 17 often supplies neither arguments nor commandLine. Minecraft already bundles JNA.
            String line = info.commandLine().orElseGet(() -> com.sun.jna.NativeLibrary.getInstance("kernel32")
                    .getFunction("GetCommandLineW").invokePointer(new Object[0]).getWideString(0));
            List<String> tokens = windowsArguments(line);
            arguments = tokens.subList(1, tokens.size());
        } else throw new IOException("Launcher arguments are unavailable; restart from your launcher");

        String main = System.getProperty("sun.java.command", "");
        int entry = -1;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (!argument.startsWith("-") && !argument.isEmpty()
                    && (main.equals(argument) || main.startsWith(argument + " "))) {
                entry = i;
                break;
            }
        }
        if (entry < 0) throw new IOException("Cannot recover the launcher entry point; restart from your launcher");

        List<String> command = new ArrayList<>();
        command.add(executable);
        // Expanded JVM arguments and classpath also work when the launcher used a temporary @argfile.
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!bootstrapOptions.isEmpty() && (argument.startsWith("-Dvulkanforge.nativeBootstrapRelaunched=")
                    || argument.matches("^-javaagent:.*[/\\\\]vulkanforge-native-bootstrap\\.jar(?:=.*)?$"))) continue;
            if (!argument.startsWith("-agentlib:jdwp") && !argument.startsWith("-Xrunjdwp")
                    && !argument.startsWith("-Dvulkanforge.startup=")) command.add(argument);
        }
        command.addAll(bootstrapOptions);
        if (entry > 0 && arguments.get(entry - 1).equals("-jar")) command.add("-jar");
        else if (entry > 0 && Set.of("-m", "--module").contains(arguments.get(entry - 1))) command.add("--module");
        else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
        }
        command.addAll(arguments.subList(entry, arguments.size()));
        return command;
    }

    /** Windows ProcessHandle exposes the raw command line on JDKs without info.arguments(). */
    static List<String> windowsArguments(String line) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
            if (i == line.length()) break;
            StringBuilder value = new StringBuilder();
            boolean quoted = false;
            while (i < line.length() && (quoted || !Character.isWhitespace(line.charAt(i)))) {
                int slashes = 0;
                while (i < line.length() && line.charAt(i) == '\\') { slashes++; i++; }
                if (i < line.length() && line.charAt(i) == '"') {
                    value.append("\\".repeat(slashes / 2));
                    if (slashes % 2 == 1) value.append('"');
                    else if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else quoted = !quoted;
                    i++;
                } else {
                    value.append("\\".repeat(slashes));
                    if (i < line.length() && (quoted || !Character.isWhitespace(line.charAt(i)))) value.append(line.charAt(i++));
                }
            }
            result.add(value.toString());
        }
        return result;
    }

    /** Separate plain Java process: wait for the old JVM, then release the helper and launch the game. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("This entry point is for the restart helper only");
        Path directory = Path.of(GameRestart.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!directory.getFileName().toString().startsWith("vulkanforge-restart-"))
            throw new IllegalArgumentException("Restart helper must run from its temporary directory");
        try {
            List<String> command = new ArrayList<>();
            ProcessBuilder builder = new ProcessBuilder();
            try (DataInputStream in = new DataInputStream(System.in)) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) command.add(new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8));
            }
            System.out.write(1); System.out.flush();
            ProcessHandle parent = ProcessHandle.of(Long.parseLong(args[0])).orElse(null);
            if (parent != null) parent.onExit().get(120, TimeUnit.SECONDS);
            builder.command(command).directory(Path.of(args[1]).toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of(args[2]).toFile())).start();
        } finally { deleteHelper(directory); }
    }

    private static void deleteHelper(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
