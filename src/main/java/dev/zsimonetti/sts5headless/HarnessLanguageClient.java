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
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LSP client for the STS5 language server. Path B role: STS5-LS's
 * {@code sts/*} project/classpath requests are <b>routed to jdt.ls</b> as
 * {@code workspace/executeCommand} on the matching {@code sts.java.*}
 * delegate (registered by STS5's bundles inside jdt.ls — see
 * docs/path-b-design.md). jdt.ls's reconcile-driven classpath then flows
 * back via {@link JdtLsClient}'s {@code executeClientCommand} relay.
 *
 * <p>The Path A self-built Maven classpath (a {@code mvn
 * dependency:build-classpath} runner + CPE shaper) is gone — jdt.ls owns
 * the project model now. See {@code docs/sizing-probe.md} for why Path A
 * was insufficient (the bean reconcile never fired) and
 * {@code docs/decision-record.md} for the final architecture.
 */
final class HarnessLanguageClient implements LanguageClient {

    private final List<WorkspaceFolder> workspaceFolders;
    private final Log log;

    /** jdt.ls proxy — bound once jdt.ls is connected (late binding). */
    private volatile LanguageServer jdtLs;

    /** Counts down when STS5 fires spring/index/updated — the proof the
     *  classpath bridge actually populated the index (watchdog waits on it). */
    private final CountDownLatch indexReady = new CountDownLatch(1);

    HarnessLanguageClient(List<WorkspaceFolder> workspaceFolders, Log log) {
        this.workspaceFolders = workspaceFolders;
        this.log = log;
    }

    void bindJdtLs(LanguageServer jdtLs) {
        this.jdtLs = jdtLs;
    }

    /** @return true if spring/index/updated arrived within the budget. */
    boolean awaitIndex(long seconds) throws InterruptedException {
        return indexReady.await(seconds, TimeUnit.SECONDS);
    }

    // --- STS5-LS sts/* -> jdt.ls sts.java.* router ---------------------------

    @JsonRequest("sts/addClasspathListener")
    public CompletableFuture<Object> addClasspathListener(ClasspathListenerParams params) {
        String callback = params == null ? null : params.callbackCommandId;
        log.line("bridge", "sts/addClasspathListener callback=" + callback
                + " -> jdt.ls sts.java.addClasspathListener");
        if (callback == null || callback.isBlank()) {
            fatal("sts/addClasspathListener with no callbackCommandId");
        }
        // vsix passes o.callbackCommandId (a String) as the single arg.
        // Listener *registration* is load-bearing — fatal on failure.
        return delegate("sts.java.addClasspathListener", List.of(callback), true);
    }

    @JsonRequest("sts/removeClasspathListener")
    public CompletableFuture<Object> removeClasspathListener(ClasspathListenerParams params) {
        String callback = params == null ? null : params.callbackCommandId;
        log.line("bridge", "sts/removeClasspathListener callback=" + callback);
        // Removal is NOT load-bearing (index already built) and STS5-LS sends
        // it during its own shutdown — failing it must not exit(2) over a
        // clean stop. Log-and-continue.
        return delegate("sts.java.removeClasspathListener",
                callback == null ? List.of() : List.of(callback), false);
    }

    /** Forward to jdt.ls as workspace/executeCommand(command, args). Standalone
     *  jdt.ls has no java.execute.workspaceCommand wrapper — call the delegate
     *  id directly (B1 finding, docs/path-b-design.md). */
    private CompletableFuture<Object> delegate(String command, List<Object> args,
                                               boolean fatalOnFailure) {
        LanguageServer js = jdtLs;
        if (js == null) {
            fatal(command + " requested before jdt.ls bound — router not ready");
        }
        return js.getWorkspaceService()
                .executeCommand(new ExecuteCommandParams(command, args))
                .exceptionally(e -> {
                    if (fatalOnFailure) {
                        // The bean index depends on this; a swallowed failure
                        // is the empty-project lie this effort exists to kill.
                        fatal("jdt.ls delegate '" + command + "' failed: " + e);
                    } else {
                        log.line("bridge", "jdt.ls delegate '" + command
                                + "' failed (non-fatal): " + e);
                    }
                    return null;
                });
    }

    /** Loud terminal failure — unless teardown already began, in which case a
     *  racing late failure is expected and must not mislabel a clean stop. */
    private void fatal(String message) {
        if (Shutdown.inProgress()) {
            log.line("shutdown", "(ignored during teardown) " + message);
            return;
        }
        log.line("fatal", message);
        System.err.println("[sts5-headless] FATAL: " + message);
        System.exit(2);
    }

    /** STS5 fires this once the Spring symbol index (re)builds. */
    @JsonNotification("spring/index/updated")
    public void springIndexUpdated(Object params) {
        log.line("index", "spring/index/updated — MCP tools should now have data");
        indexReady.countDown();
    }

    // --- server -> client notifications: log them ---------------------------

    @Override
    public void telemetryEvent(Object object) {
        log.line("telemetry", String.valueOf(object));
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        int n = diagnostics.getDiagnostics() == null ? 0 : diagnostics.getDiagnostics().size();
        if (n > 0) {
            log.line("diagnostics", n + " in " + diagnostics.getUri());
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        log.line("server-message", messageParams.getType() + ": " + messageParams.getMessage());
    }

    @Override
    public void logMessage(MessageParams message) {
        log.line("server-log", message.getType() + ": " + message.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        log.line("server-message-request", params.getMessage());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(org.eclipse.lsp4j.ProgressParams params) {
        log.line("progress", String.valueOf(params.getValue()));
    }

    // --- server -> client requests we MUST answer for init to complete ------

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        int n = configurationParams.getItems() == null ? 0 : configurationParams.getItems().size();
        List<Object> answer = new ArrayList<>(Collections.nCopies(n, null));
        log.line("configuration", "answered " + n + " item(s) with defaults");
        return CompletableFuture.completedFuture(answer);
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return CompletableFuture.completedFuture(workspaceFolders);
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
