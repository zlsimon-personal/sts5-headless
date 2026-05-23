package dev.zsimonetti.sts5headless;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Dead-simple timestamped stdout logger — this is a prototype, not prod. */
final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    void line(String tag, String message) {
        System.out.println(LocalTime.now().format(TS) + "  [" + tag + "] " + message);
    }
}
