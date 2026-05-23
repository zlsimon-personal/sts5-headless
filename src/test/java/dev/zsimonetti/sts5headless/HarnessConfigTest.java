package dev.zsimonetti.sts5headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessConfigTest {

    @Test
    void shouldResolveProjectPathFromFirstArg(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));

        HarnessConfig cfg = HarnessConfig.resolve(
                new String[]{project.toString()},
                key -> "sts5.ls.jar".equals(key) ? lsJar.toString() : null);

        assertEquals(project.toRealPath(), cfg.projectPath());
    }

    @Test
    void shouldRejectMissingProjectArg() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{}, key -> null));
        assertTrue(ex.getMessage().toLowerCase().contains("project"),
                "message should name the missing project arg, was: " + ex.getMessage());
    }

    @Test
    void shouldRejectNonExistentProjectPath() {
        assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{"/no/such/dir/here"}, key -> null));
    }

    @Test
    void shouldDefaultMcpPortTo50627(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));

        HarnessConfig cfg = HarnessConfig.resolve(
                new String[]{project.toString()},
                key -> "sts5.ls.jar".equals(key) ? lsJar.toString() : null);

        assertEquals(50627, cfg.mcpPort());
    }

    @Test
    void shouldOverrideMcpPortFromSystemProperty(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));

        HarnessConfig cfg = HarnessConfig.resolve(
                new String[]{project.toString()},
                key -> switch (key) {
                    case "sts5.ls.jar" -> lsJar.toString();
                    case "sts5.mcp.port" -> "51999";
                    default -> null;
                });

        assertEquals(51999, cfg.mcpPort());
    }

    @Test
    void shouldRejectLsJarThatDoesNotExist(@TempDir Path project) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(
                        new String[]{project.toString()},
                        key -> "sts5.ls.jar".equals(key) ? "/no/such/ls.jar" : null));
        assertTrue(ex.getMessage().toLowerCase().contains("language-server")
                        || ex.getMessage().toLowerCase().contains("jar"),
                "message should explain the missing LS jar, was: " + ex.getMessage());
    }

    @Test
    void shouldRejectBlankProjectArg() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{"   "}, key -> null));
        assertTrue(ex.getMessage().toLowerCase().contains("project"),
                "message should name the project arg, was: " + ex.getMessage());
    }

    @Test
    void shouldRejectProjectPathThatIsAFileNotDirectory(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("not-a-dir"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{file.toString()}, key -> null));
        assertTrue(ex.getMessage().toLowerCase().contains("directory"),
                "message should explain it must be a directory, was: " + ex.getMessage());
    }

    @Test
    void shouldRejectNonNumericMcpPort(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{project.toString()},
                        key -> switch (key) {
                            case "sts5.ls.jar" -> lsJar.toString();
                            case "sts5.mcp.port" -> "not-a-port";
                            default -> null;
                        }));
    }

    @Test
    void shouldRejectMcpPortOutOfRange(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{project.toString()},
                        key -> switch (key) {
                            case "sts5.ls.jar" -> lsJar.toString();
                            case "sts5.mcp.port" -> "70000";
                            default -> null;
                        }));
    }

    @Test
    void shouldDefaultInitTimeoutTo60Seconds(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));

        HarnessConfig cfg = HarnessConfig.resolve(
                new String[]{project.toString()},
                key -> "sts5.ls.jar".equals(key) ? lsJar.toString() : null);

        assertEquals(Duration.ofSeconds(60), cfg.initTimeout());
    }

    @Test
    void shouldOverrideInitTimeoutFromSystemProperty(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));

        HarnessConfig cfg = HarnessConfig.resolve(
                new String[]{project.toString()},
                key -> switch (key) {
                    case "sts5.ls.jar" -> lsJar.toString();
                    case "sts5.init.timeout.seconds" -> "15";
                    default -> null;
                });

        assertEquals(Duration.ofSeconds(15), cfg.initTimeout());
    }

    @Test
    void shouldRejectNonPositiveInitTimeout(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{project.toString()},
                        key -> switch (key) {
                            case "sts5.ls.jar" -> lsJar.toString();
                            case "sts5.init.timeout.seconds" -> "0";
                            default -> null;
                        }));
    }

    @Test
    void shouldRejectNonNumericInitTimeout(@TempDir Path project) throws Exception {
        Path lsJar = Files.createFile(project.resolve("ls.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> HarnessConfig.resolve(new String[]{project.toString()},
                        key -> switch (key) {
                            case "sts5.ls.jar" -> lsJar.toString();
                            case "sts5.init.timeout.seconds" -> "soon";
                            default -> null;
                        }));
    }
}
