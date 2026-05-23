package dev.zsimonetti.sts5headless;

import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole Path-A bridge hinges on LSP4J discovering our
 * {@code @JsonRequest("sts/addClasspathListener")} handler. If LSP4J does not
 * register it, STS5's request is answered "Unsupported request method" and the
 * project is never indexed. This pins discovery so a refactor can't silently
 * break it.
 */
class ClasspathBridgeRegistrationTest {

    @Test
    void lsp4jDiscoversTheStsClasspathRequestHandlers() {
        Set<String> methods =
                ServiceEndpoints.getSupportedMethods(HarnessLanguageClient.class).keySet();

        assertTrue(methods.contains("sts/addClasspathListener"),
                "LSP4J must register sts/addClasspathListener; discovered: " + methods);
        assertTrue(methods.contains("sts/removeClasspathListener"),
                "LSP4J must register sts/removeClasspathListener; discovered: " + methods);
    }
}
