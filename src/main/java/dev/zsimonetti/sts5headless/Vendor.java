package dev.zsimonetti.sts5headless;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves the {@code vendor/} root (fetched STS5 + jdt.ls runtimes) so the
 * jar is <b>relocatable</b> — not pinned to the process working directory
 * (release BLOCKER #2, docs/release-readiness.md).
 *
 * <p>Precedence:
 * <ol>
 *   <li>{@code -Dsts5.vendor.dir=<dir>} — explicit override (validated; a
 *       non-existent override fails loud, not silently).</li>
 *   <li>When launched from the packaged jar: {@code <jarDir>/vendor}
 *       (a relocated/installed layout — vendor beside the jar) or
 *       {@code <jarDir>/../vendor} (the in-repo {@code target/} layout).
 *       If it's a packaged jar but <b>neither</b> exists, fail loud with the
 *       searched paths — never silently fall to cwd (that silent fallback
 *       was the exact footgun #2 exists to remove).</li>
 *   <li>Otherwise (running from {@code target/classes} — tests/IDE):
 *       {@code ./vendor} relative to cwd.</li>
 * </ol>
 *
 * <p>Resolution is memoized; it reads system properties / the code source at
 * first use (runtime, after the JVM has its {@code -D}s), not at class-init,
 * so a failure surfaces as a normal actionable exception (caught by
 * {@code HarnessMain.main}) rather than an {@code ExceptionInInitializerError}.
 */
final class Vendor {

    private static volatile Path cached;

    private Vendor() {
    }

    /** Production entry point — memoized; reads {@code System::getProperty}
     *  and this class's code source. */
    static Path dir() {
        Path c = cached;
        if (c == null) {
            c = resolve(System::getProperty, Vendor::ownCodeSourceLocation);
            cached = c;
        }
        return c;
    }

    /** Testable seam: {@code props} supplies {@code sts5.vendor.dir};
     *  {@code codeSource} supplies the launch location (a {@code file:} jar
     *  URL, a directory URL, or null). Not memoized. */
    static Path resolve(Function<String, String> props, Supplier<URL> codeSource) {
        String override = props.apply("sts5.vendor.dir");
        if (override != null && !override.isBlank()) {
            Path o = Path.of(override);
            if (!Files.isDirectory(o)) {
                throw new IllegalStateException(
                        "sts5.vendor.dir does not point at a directory: " + override);
            }
            return o;
        }

        Path jar = packagedJarPath(codeSource);
        if (jar != null) {
            Path jarDir = jar.getParent();
            if (jarDir != null) {
                Path beside = jarDir.resolve("vendor");          // relocated install
                if (Files.isDirectory(beside)) {
                    return beside;
                }
                Path parent = jarDir.getParent();
                if (parent != null) {
                    Path inRepo = parent.resolve("vendor");        // in-repo target/
                    if (Files.isDirectory(inRepo)) {
                        return inRepo;
                    }
                }
            }
            // Packaged jar but no colocated vendor → loud, not silent cwd.
            throw new IllegalStateException(
                    "no vendor/ found next to the jar (" + jar + ") nor at its"
                    + " parent — set -Dsts5.vendor.dir=<dir> (run the README"
                    + " vendor setup). Refusing to silently use ./vendor.");
        }

        // Running from classes (tests / IDE) — cwd ./vendor is the dev path.
        return Path.of("vendor");
    }

    /** The launch path iff it is a regular file (a packaged jar); null when
     *  it's a directory ({@code target/classes}) or unavailable. */
    private static Path packagedJarPath(Supplier<URL> codeSource) {
        URL loc;
        try {
            loc = codeSource.get();
        } catch (RuntimeException e) {
            return null;
        }
        if (loc == null || !"file".equalsIgnoreCase(loc.getProtocol())) {
            return null;
        }
        Path p;
        try {
            p = Path.of(loc.toURI());
        } catch (java.net.URISyntaxException | IllegalArgumentException
                 | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
        return Files.isRegularFile(p) ? p : null;
    }

    private static URL ownCodeSourceLocation() {
        var src = Vendor.class.getProtectionDomain().getCodeSource();
        return src == null ? null : src.getLocation();
    }
}
