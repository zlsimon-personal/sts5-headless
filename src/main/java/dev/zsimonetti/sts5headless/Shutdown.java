package dev.zsimonetti.sts5headless;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared "teardown has begun" flag. Failures that race with shutdown
 * (a late classpath callback, STS5-LS or jdt.ls already gone) must not be
 * mislabeled as fatal crashes — a clean stop looking like exit code 2 is
 * the inverse of the fail-loud rule. {@code fatal()} paths consult this and
 * downgrade to a log line once teardown is in progress.
 */
final class Shutdown {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static void begin() {
        STARTED.set(true);
    }

    static boolean inProgress() {
        return STARTED.get();
    }

    private Shutdown() {
    }
}
