package dev.zsimonetti.sts5headless;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Resolved launch configuration for the harness.
 *
 * <p>The harness is a thin LSP client + subprocess supervisor (see
 * {@code docs/harness-design.md}); this record is the one piece of pure,
 * unit-testable logic — turning argv + system properties into validated paths.
 *
 * @param projectPath the Spring project to open as an LSP workspace folder
 * @param lsJar       the self-contained STS5 language-server exec jar
 * @param mcpPort     the port the LS's embedded MCP server binds (passed as
 *                    {@code -Dserver.port}; default matches the IDE setting)
 * @param initTimeout how long to wait for the LSP {@code initialize} round-trip
 */
public record HarnessConfig(Path projectPath, Path lsJar, int mcpPort, Duration initTimeout) {

    /** Default MCP port — matches the {@code boot-java.ai.mcp-server-port} VS Code setting. */
    public static final int DEFAULT_MCP_PORT = 50627;

    /** Resolved at use-time (not class-init) via the memoized
     *  {@link Vendor#dir()} so a bad/missing vendor root fails loud as a
     *  normal exception, not an ExceptionInInitializerError. */
    private static Path defaultLsDir() {
        return Vendor.dir().resolve("vsix-extracted").resolve("extension")
                .resolve("language-server");
    }
    private static final String LS_JAR_GLOB = "spring-boot-language-server-*-exec.jar";

    /**
     * Resolve and validate config from command-line args and a property lookup.
     *
     * @param args  argv; {@code args[0]} is the project path (required)
     * @param props property resolver — {@code System::getProperty} in prod,
     *              a map in tests. Honors {@code sts5.ls.jar},
     *              {@code sts5.mcp.port}, {@code sts5.init.timeout.seconds}.
     * @throws IllegalArgumentException with an actionable message on any
     *         missing/invalid input — failures are loud, never silently defaulted.
     */
    public static HarnessConfig resolve(String[] args, Function<String, String> props) {
        if (args.length < 1 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "project path argument required: java -jar sts5-headless.jar <spring-project-dir>");
        }

        Path projectPath;
        try {
            projectPath = Path.of(args[0]).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "project path does not exist or is not accessible: " + args[0], e);
        }
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("project path is not a directory: " + projectPath);
        }

        Path lsJar = resolveLsJar(props.apply("sts5.ls.jar"));

        int mcpPort = parsePort(props.apply("sts5.mcp.port"));

        Duration initTimeout = Duration.ofSeconds(
                parsePositiveLong(props.apply("sts5.init.timeout.seconds"), 60));

        return new HarnessConfig(projectPath, lsJar, mcpPort, initTimeout);
    }

    private static Path resolveLsJar(String override) {
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException(
                        "sts5.ls.jar does not point at a file: " + override);
            }
            return p.toAbsolutePath();
        }
        Path lsDir = defaultLsDir();
        if (!Files.isDirectory(lsDir)) {
            throw new IllegalArgumentException(
                    "no language-server jar found — expected " + lsDir
                            + " (run the vendor setup in the README), "
                            + "or set -Dsts5.ls.jar=<path-to-exec.jar>");
        }
        try (Stream<Path> s = Files.list(lsDir)) {
            return s.filter(p -> p.getFileName().toString().matches(
                            LS_JAR_GLOB.replace(".", "\\.").replace("*", ".*")))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no " + LS_JAR_GLOB + " under " + lsDir
                                    + " — set -Dsts5.ls.jar=<path-to-exec.jar>"))
                    .toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("failed listing " + lsDir, e);
        }
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MCP_PORT;
        }
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sts5.mcp.port is not a number: " + raw, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("sts5.mcp.port out of range 1..65535: " + port);
        }
        return port;
    }

    private static long parsePositiveLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v <= 0) {
                throw new IllegalArgumentException("expected a positive number, got: " + raw);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + raw, e);
        }
    }
}
