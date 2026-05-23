package dev.zsimonetti.sts5headless;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Launches a standalone Eclipse JDT Language Server (jdt.ls) subprocess with
 * STS5's {@code jars/*} extension bundles. jdt.ls owns the real project model
 * (m2e import + reconcile) — Path B's whole point: it produces the classpath
 * that drives STS5's bean reconcile, which Path A could not.
 *
 * <p>jdt.ls speaks LSP over <b>stdio</b>. The extension bundles are passed in
 * the LSP {@code initialize} via {@code initializationOptions.bundles} (see
 * {@link JdtLsClient}/the launcher), not on the command line.
 *
 * <p>B1 proved jdt.ls 1.58.0 + the 5 STS5 bundles registers all
 * {@code sts.java.*} delegate commands (docs/path-b-design.md → "B1 RESULT").
 */
final class JdtLs {

    /** STS5's javaExtensions bundles (package.json contributes.javaExtensions). */
    static final List<String> BUNDLE_NAMES = List.of(
            "io.projectreactor.reactor-core",
            "org.reactivestreams.reactive-streams",
            "jdt-ls-commons",
            "jdt-ls-extension",
            "sts-gradle-tooling");

    // Resolved at use-time via memoized Vendor.dir() (not class-init) so a
    // missing/relocated vendor root fails loud as a normal exception.
    private static Path jdtlsHome() {
        return Vendor.dir().resolve("jdtls");
    }
    private static Path stsJarsDir() {
        return Vendor.dir().resolve("vsix-extracted").resolve("extension")
                .resolve("jars");
    }

    private JdtLs() {
    }

    /** Absolute paths of the STS5 extension bundles for initializationOptions.bundles. */
    static List<String> bundlePaths() {
        List<String> out = new ArrayList<>();
        for (String b : BUNDLE_NAMES) {
            Path p = stsJarsDir().resolve(b + ".jar");
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("missing STS5 bundle " + p
                        + " — run the vsix extraction (Task 2)");
            }
            out.add(p.toAbsolutePath().toString());
        }
        return out;
    }

    /** Spawn jdt.ls; stdout/stderr is the LSP stream (do NOT redirect to a file). */
    static Process spawn(Path dataDir, Log log) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path launcher = equinoxLauncher();
        Path config = jdtlsConfigDir();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create jdt.ls data dir " + dataDir, e);
        }
        List<String> cmd = List.of(
                javaBin,
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=ALL",
                "-Xmx1G",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", launcher.toString(),
                "-configuration", config.toString(),
                "-data", dataDir.toAbsolutePath().toString());
        log.line("jdtls", "spawning: jdt.ls (" + launcher.getFileName()
                + ", " + config.getFileName() + ", -data " + dataDir + ")");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // stdout = LSP; route stderr to the harness for diagnostics.
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start jdt.ls", e);
        }
    }

    private static Path equinoxLauncher() {
        Path plugins = jdtlsHome().resolve("plugins");
        try (Stream<Path> s = Files.list(plugins)) {
            return s.filter(p -> p.getFileName().toString()
                            .matches("org\\.eclipse\\.equinox\\.launcher_.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no equinox launcher under " + plugins))
                    .toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + plugins
                    + " — is jdt.ls extracted to vendor/jdtls?", e);
        }
    }

    /** Pick the jdt.ls config dir for this OS/arch. */
    private static Path jdtlsConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        String dir;
        if (os.contains("mac") || os.contains("darwin")) {
            dir = arm ? "config_mac_arm" : "config_mac";
        } else if (os.contains("win")) {
            dir = "config_win";
        } else {
            dir = arm ? "config_linux_arm" : "config_linux";
        }
        Path p = jdtlsHome().resolve(dir);
        if (!Files.isDirectory(p)) {
            throw new IllegalStateException("jdt.ls config dir not found: " + p);
        }
        return p.toAbsolutePath();
    }
}
