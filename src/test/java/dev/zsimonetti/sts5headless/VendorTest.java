package dev.zsimonetti.sts5headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vendor-dir resolution precedence + the jar-relative branch that IS
 * release BLOCKER #2 (relocatable jar). Exercised through the
 * {@code resolve(props, codeSource)} seam — no real packaging needed.
 */
class VendorTest {

    private static Function<String, String> noProps() {
        return k -> null;
    }

    private static Supplier<URL> fileUrl(Path p) {
        return () -> {
            try {
                return p.toUri().toURL();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Test
    void explicitOverrideWins(@TempDir Path dir) {
        assertEquals(dir, Vendor.resolve(
                k -> "sts5.vendor.dir".equals(k) ? dir.toString() : null,
                () -> null));
    }

    @Test
    void overrideThatIsNotADirectoryFailsLoud() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Vendor.resolve(
                        k -> "sts5.vendor.dir".equals(k) ? "/no/such/vendor" : null,
                        () -> null));
        assertTrue(ex.getMessage().contains("sts5.vendor.dir"));
    }

    @Test
    void relocatedInstall_vendorBesideJar(@TempDir Path inst) throws Exception {
        Path jar = Files.createFile(inst.resolve("sts5-headless.jar"));
        Path vendor = Files.createDirectories(inst.resolve("vendor"));
        assertEquals(vendor, Vendor.resolve(noProps(), fileUrl(jar)));
    }

    @Test
    void inRepoLayout_vendorAtJarGrandparent(@TempDir Path repo) throws Exception {
        Path jar = Files.createFile(
                Files.createDirectories(repo.resolve("target")).resolve("sts5-headless.jar"));
        Path vendor = Files.createDirectories(repo.resolve("vendor"));
        assertEquals(vendor, Vendor.resolve(noProps(), fileUrl(jar)));
    }

    @Test
    void packagedJarButNoVendorAnywhere_failsLoud_notSilentCwd(@TempDir Path inst)
            throws Exception {
        Path jar = Files.createFile(
                Files.createDirectories(inst.resolve("target")).resolve("sts5-headless.jar"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Vendor.resolve(noProps(), fileUrl(jar)));
        assertTrue(ex.getMessage().contains("sts5.vendor.dir"),
                "must point the user at the override, not silently use ./vendor");
    }

    @Test
    void runningFromClassesDir_fallsBackToCwdVendor(@TempDir Path classes) {
        // codeSource is a directory (target/classes) → not a packaged jar
        assertEquals(Path.of("vendor"), Vendor.resolve(noProps(), fileUrl(classes)));
    }

    @Test
    void noCodeSource_fallsBackToCwdVendor() {
        assertEquals(Path.of("vendor"), Vendor.resolve(noProps(), () -> null));
    }
}
