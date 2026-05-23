package dev.zsimonetti.sts5headless;

import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * LSP client for the jdt.ls subprocess. Its one load-bearing job: relay
 * jdt.ls's {@code workspace/executeClientCommand} (how jdt.ls invokes the
 * classpath callback STS5-LS registered) on to STS5-LS as a
 * {@code workspace/executeCommand}. That closes the Path B loop: jdt.ls's
 * real reconcile-driven classpath reaches STS5-LS, which then populates the
 * bean index the MCP tools read.
 */
final class JdtLsClient implements LanguageClient {

    private final Log log;
    /** STS5-LS proxy — bound once STS5-LS is connected. */
    private volatile LanguageServer stsLs;
    private volatile boolean ready;

    JdtLsClient(Log log) {
        this.log = log;
    }

    /** Notified (projectUri, name) each time jdt.ls delivers a project's
     *  classpath — the readiness signal that carries which project. */
    private volatile BiConsumer<String, String> onClasspath = (u, n) -> {};

    void bindStsLs(LanguageServer stsLs) {
        this.stsLs = stsLs;
    }

    void onClasspathDelivered(BiConsumer<String, String> cb) {
        this.onClasspath = cb;
    }

    boolean isReady() {
        return ready;
    }

    // --- the Path B relay ----------------------------------------------------

    /** jdt.ls -> client: run a command on the client. STS5-LS registered the
     *  classpath callbackCommandId via onCommand(), so forward it there. */
    @JsonRequest("workspace/executeClientCommand")
    public CompletableFuture<Object> executeClientCommand(ExecuteCommandParams params) {
        String cmd = params == null ? null : params.getCommand();
        if (stsLs == null) {
            log.line("jdtls", "executeClientCommand '" + cmd + "' but STS5-LS not bound yet");
            return CompletableFuture.completedFuture(null);
        }
        // The classpath callback's args are [projectUri, name, deleted,
        // classpath, ...] (ClasspathListener$Event order). projectUri+name
        // is the per-project readiness signal + the path<->name map source.
        List<Object> a = params.getArguments();
        String uri = null, name = null;
        if (a != null && a.size() >= 2 && a.get(0) != null && a.get(1) != null) {
            uri = String.valueOf(a.get(0)).replaceAll("^\"|\"$", "");
            name = String.valueOf(a.get(1)).replaceAll("^\"|\"$", "");
            log.line("map", "projectUri=" + uri + "  name=" + name);
        }
        final String fUri = uri, fName = name;
        log.line("bridge", "jdt.ls -> STS5-LS relay: " + cmd);
        // Relay verbatim; STS5-LS's onCommand(callbackCommandId) consumes it.
        // Readiness (onClasspath) fires only AFTER STS5-LS accepts the relay —
        // "ready" must mean STS5-LS received the classpath for this project,
        // not merely that jdt.ls emitted it. A relay reject isn't necessarily
        // terminal (jdt.ls may resend); HarnessMain's index watchdog catches a
        // genuinely stalled index.
        return stsLs.getWorkspaceService()
                .executeCommand(new ExecuteCommandParams(cmd, params.getArguments()))
                .thenApply(r -> {
                    if (fUri != null) {
                        try {
                            onClasspath.accept(fUri, fName);
                        } catch (RuntimeException ignore) {
                            // bookkeeping must never break the relay result
                        }
                    }
                    return r;
                })
                .exceptionally(e -> {
                    log.line(Shutdown.inProgress() ? "shutdown" : "fatal",
                            "classpath relay '" + cmd + "' to STS5-LS FAILED: " + e
                                    + " (watchdog will catch a stalled index)");
                    return null;
                });
    }

    // --- jdt.ls lifecycle signals -------------------------------------------

    @JsonNotification("language/status")
    public void languageStatus(Object params) {
        String s = String.valueOf(params);
        if (s.contains("ServiceReady") || s.contains("Started")) {
            ready = true;
            log.line("jdtls", "status: " + s);
        }
    }

    @JsonNotification("language/eventNotification")
    public void languageEvent(Object params) {
        log.line("jdtls", "event: " + params);
    }

    // --- standard LSP client surface (log / answer to not stall) ------------

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
    }

    @Override
    public void showMessage(MessageParams m) {
        log.line("jdtls-msg", m.getType() + ": " + m.getMessage());
    }

    @Override
    public void logMessage(MessageParams m) {
        // jdt.ls is very chatty; only surface warnings/errors.
        if (m.getType() != null && m.getType().getValue() <= 2) {
            log.line("jdtls-log", m.getType() + ": " + m.getMessage());
        }
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(org.eclipse.lsp4j.ProgressParams params) {
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams p) {
        int n = p.getItems() == null ? 0 : p.getItems().size();
        return CompletableFuture.completedFuture(
                new ArrayList<>(Collections.nCopies(n, null)));
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }
}
