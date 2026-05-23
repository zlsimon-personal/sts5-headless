package dev.zsimonetti.sts5headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The readiness latch is keyed by {@link HarnessMain#canon}. If the form
 * jdt.ls emits ({@code file:/Users/...}, single slash) and the form we
 * derive from a folder ({@code file:///Users/...}) and the raw path don't
 * all canon to the same key, the latch never fires and ensureWorkspace
 * times out for a project that actually indexed (review CR-002). Pin it.
 */
class CanonTest {

    @Test
    void rawPath_tripleSlashUri_and_jdtSingleSlashUri_canonToSameKey(
            @TempDir Path dir) {
        Path real = dir; // exists → toRealPath() succeeds (the happy path)
        String fromRawPath = HarnessMain.canon(real.toString());
        String fromFolderUri = HarnessMain.canon(real.toUri().toString());     // file:///...
        String fromJdtUri = HarnessMain.canon("file:" + real);                 // file:/...  (jdt.ls form)

        assertEquals(fromRawPath, fromFolderUri,
                "raw path vs file:/// folder URI must canon equal");
        assertEquals(fromRawPath, fromJdtUri,
                "raw path vs jdt.ls file:/ single-slash URI must canon equal");
    }

    @Test
    void trailingSlashUriIsIrrelevant(@TempDir Path dir) {
        assertEquals(
                HarnessMain.canon(dir.toUri().toString()),
                HarnessMain.canon(dir.toUri().toString().replaceAll("/+$", "") + "/"));
    }
}
