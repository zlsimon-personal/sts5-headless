package dev.zsimonetti.sts5headless;

import com.sun.net.httpserver.HttpServer;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Comparator;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Path B harness: run jdt.ls (real project model, loaded with STS5's
 * extension bundles) AND the STS5 language server, and route STS5-LS's
 * {@code sts/*} requests to jdt.ls's {@code sts.java.*} delegates. jdt.ls's
 * reconcile-driven classpath flows back to STS5-LS, populating the bean
 * index the MCP tools serve.
 *
 * <p>Usage: {@code java -jar sts5-headless.jar <spring-project-dir>}.
 * Design + the request→delegate contract: {@code docs/path-b-design.md}.
 */
public final class HarnessMain {

    private static final int CONNECT_TIMEOUT_MS = 60_000;
    private static final int CONNECT_BACKOFF_MS = 250;
    private static final int CONNECT_ATTEMPT_MS = 1_000;
    /** jdt.ls must reach ServiceReady before STS5-LS may send sts/* (else a
     *  cold-start race; m2e import is slow, so this is generous). */
    private static final int JDTLS_READY_TIMEOUT_MS = 240_000;
    /** After STS5-LS init, spring/index/updated must arrive or the classpath
     *  bridge stalled and MCP would serve an empty index — fail loud. */
    private static final long INDEX_WATCHDOG_SECONDS = 300;
    /** Budget for a dynamically-added workspace to get its classpath. */
    private static final long ENSURE_BUDGET_SECONDS = 240;

    public static void main(String[] args) {
        Log log = new Log();
        HarnessConfig cfg = HarnessConfig.resolve(args, System::getProperty);
        log.line("config", "project=" + cfg.projectPath());
        log.line("config", "ls-jar=" + cfg.lsJar());
        log.line("config", "mcp-port=" + cfg.mcpPort());

        Harness h = new Harness(log);
        Runtime.getRuntime().addShutdownHook(new Thread(h::teardown, "sts5-shutdown"));
        try {
            int code = h.run(cfg);
            h.teardown();
            System.exit(code);
        } catch (Throwable t) {
            log.line("fatal", t.getClass().getSimpleName() + ": " + t.getMessage());
            h.teardown();
            System.exit(1);
        }
    }

    /** Owns both subprocesses + both LSP clients; one idempotent teardown. */
    private static final class Harness {
        private final Log log;
        private final AtomicBoolean torndown = new AtomicBoolean(false);

        private volatile Process jdtLsProc;
        private volatile Process stsProc;
        private volatile Socket socket;
        private volatile Future<Void> jdtListening;
        private volatile Future<Void> stsListening;
        private volatile ExecutorService jdtExecutor;
        private volatile ExecutorService stsExecutor;
        private volatile Path jdtWs;

        // --- M1 multi-project state ---
        private final Set<String> activePaths = ConcurrentHashMap.newKeySet();
        private final Map<String, String> pathToName = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> readyLatch = new ConcurrentHashMap<>();
        private volatile LanguageServer jdtServerRef;
        private volatile LanguageServer stsServerRef;
        private volatile HttpServer control;
        private volatile ExecutorService controlExec;
        private volatile ScopingProxy proxy;
        private volatile ExecutorService proxyExec;

        Harness(Log log) {
            this.log = log;
        }

        int run(HarnessConfig cfg) throws Exception {
            // Initial workspaces: primary from argv + optional extra dirs
            // via -Dsts5.extra.projects=/p2,/p3 — all sent as
            // workspaceFolders to BOTH jdt.ls and STS5-LS at init. More can
            // be added later at runtime via the /ensureWorkspace control.
            List<WorkspaceFolder> folders = new ArrayList<>();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            seen.add(canon(cfg.projectPath().toUri().toString()));
            folders.add(workspaceFolder(cfg.projectPath()));
            String extra = System.getProperty("sts5.extra.projects", "");
            for (String p : extra.split(",")) {
                if (p.isBlank()) {
                    continue;
                }
                WorkspaceFolder wf = workspaceFolder(Path.of(p.trim()));
                if (seen.add(canon(wf.getUri()))) { // dedup vs primary/repeats
                    folders.add(wf);
                }
            }
            log.line("config", "workspaceFolders=" + folders.stream()
                    .map(WorkspaceFolder::getName).toList());
            folders.forEach(this::track);

            // --- 1. jdt.ls first: it must be bound before STS5-LS sends sts/* ---
            jdtWs = Files.createTempDirectory("sts5-jdtls-ws");
            jdtLsProc = JdtLs.spawn(jdtWs, log);
            jdtExecutor = daemonPool("sts5-jdtls");
            JdtLsClient jdtClient = new JdtLsClient(log);
            jdtClient.onClasspathDelivered(this::onClasspath);
            Launcher<LanguageServer> jdtLauncher = lspLauncher(
                    jdtClient, jdtLsProc.getInputStream(), jdtLsProc.getOutputStream(),
                    jdtExecutor);
            LanguageServer jdtServer = jdtLauncher.getRemoteProxy();
            jdtServerRef = jdtServer;
            jdtListening = jdtLauncher.startListening();
            log.line("jdtls", "initialize (bundles=" + JdtLs.BUNDLE_NAMES + ") ...");
            jdtServer.initialize(jdtInitParams(folders)).get(
                    cfg.initTimeout().toSeconds(), TimeUnit.SECONDS);
            jdtServer.initialized(new InitializedParams());
            log.line("jdtls", "initialized — importing the Maven project; "
                    + "awaiting jdt.ls ServiceReady before starting STS5-LS");
            long jdtDeadline = System.currentTimeMillis() + JDTLS_READY_TIMEOUT_MS;
            while (!jdtClient.isReady()) {
                if (!jdtLsProc.isAlive()) {
                    throw new IllegalStateException("jdt.ls exited (code "
                            + jdtLsProc.exitValue() + ") before ServiceReady");
                }
                if (System.currentTimeMillis() > jdtDeadline) {
                    throw new IllegalStateException("jdt.ls never reached "
                            + "ServiceReady within " + JDTLS_READY_TIMEOUT_MS
                            + "ms — cannot route sts/* safely");
                }
                Thread.sleep(250);
            }
            log.line("jdtls", "ServiceReady — project model up; starting STS5-LS");

            // --- 2. STS5-LS over its standalone socket (Path A plumbing) ---
            int lspPort = freePort();
            stsProc = spawnStsLs(cfg, lspPort, log);
            log.line("lsp", "connecting to standalone STS5-LS on 127.0.0.1:" + lspPort);
            socket = connectWithRetry(lspPort, stsProc, log);
            log.line("lsp", "connected to STS5-LS");
            stsExecutor = daemonPool("sts5-lsp");
            HarnessLanguageClient stsClient = new HarnessLanguageClient(folders, log);
            Launcher<LanguageServer> stsLauncher = lspLauncher(
                    stsClient, socket.getInputStream(), socket.getOutputStream(),
                    stsExecutor);
            LanguageServer stsServer = stsLauncher.getRemoteProxy();
            stsServerRef = stsServer;

            // --- 3. cross-wire the router (before either starts dispatching) ---
            stsClient.bindJdtLs(jdtServer);
            jdtClient.bindStsLs(stsServer);

            stsListening = stsLauncher.startListening();
            InitializeResult r = stsServer.initialize(stsInitParams(folders)).get(
                    cfg.initTimeout().toSeconds(), TimeUnit.SECONDS);
            log.line("lsp", "STS5-LS initialize OK: " + r.getCapabilities());
            stsServer.initialized(new InitializedParams());

            // CR-005: start control + scoping proxy BEFORE announcing ready,
            // so a bind failure fails loud before MCP looks healthy.
            startControlServer(cfg.mcpPort() + 1);
            proxyExec = daemonPool("sts5-proxy");
            proxy = new ScopingProxy(cfg.mcpPort(),
                    ws -> pathToName.get(canon(ws)), log);
            proxy.start(cfg.mcpPort() + 2, proxyExec);

            // CR-003: gate readiness on EVERY initial workspace's classpath,
            // not just the first spring/index/updated. Loud per missed one
            // (don't nuke a multi-init over one bad project; the index
            // watchdog still exits if NO project ever indexes).
            awaitInitialWorkspaces();

            log.line("ready", "router live. Scoped MCP (use this): "
                    + "http://localhost:" + (cfg.mcpPort() + 2) + "/mcp"
                    + " with header " + ScopingProxy.WORKSPACE_HEADER
                    + ":<project-path>. Raw STS MCP :" + cfg.mcpPort()
                    + " (internal). Ctrl-C / SIGTERM to stop.");

            // Watchdog: if the classpath never effectively reaches STS5-LS,
            // spring/index/updated never fires and MCP serves an empty index
            // while looking healthy. Turn that silent lie into a loud exit.
            startIndexWatchdog(stsClient, log);

            int code = stsProc.waitFor();
            log.line("exit", "STS5-LS exited with code " + code);
            return code;
        }

        void teardown() {
            if (!torndown.compareAndSet(false, true)) {
                return;
            }
            Shutdown.begin(); // racing late failures now downgrade, not exit(2)
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {
                    log.line("teardown", "socket close failed: " + e);
                }
            }
            // control.stop(0) doesn't interrupt in-flight handlers;
            // shut(controlExec)=shutdownNow() interrupts pool threads, which
            // unblocks any ensureWorkspace parked in latch.await() (await is
            // interruptible → returns "ERR interrupted"). Order matters: do
            // this before killing jdt.ls so an in-flight add fails cleanly.
            if (proxy != null) {
                proxy.stop();
            }
            shut(proxyExec);
            if (control != null) {
                control.stop(0);
            }
            shut(controlExec);
            cancel(stsListening);
            cancel(jdtListening);
            shut(stsExecutor);
            shut(jdtExecutor);
            kill("STS5-LS", stsProc);
            kill("jdt.ls", jdtLsProc);
            deleteRecursively(jdtWs); // after jdt.ls released its workspace lock
        }

        private void deleteRecursively(Path dir) {
            if (dir == null) {
                return;
            }
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignore) {
                                // best-effort; OS temp cleanup is the backstop
                            }
                        });
            } catch (IOException e) {
                log.line("teardown", "jdt.ls workspace cleanup skipped: " + e);
            }
        }

        private void cancel(Future<?> f) {
            if (f != null) {
                f.cancel(true);
            }
        }

        private void shut(ExecutorService e) {
            if (e != null) {
                e.shutdownNow();
            }
        }

        private void kill(String name, Process p) {
            if (p != null && p.isAlive()) {
                log.line("shutdown", "terminating " + name);
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
        }

        // --- M1: multi-project control --------------------------------------

        /** jdt.ls delivered a project's classpath — record name, mark ready. */
        private void onClasspath(String uri, String name) {
            String cp = canon(uri);
            pathToName.put(cp, name);
            CountDownLatch l = readyLatch.get(cp);
            if (l != null) {
                l.countDown();
            } else {
                // CR-002: a classpath for a key we don't track means canon()
                // diverged (e.g. asymmetric toRealPath). Make it loud — else
                // it only shows as a phantom ENSURE_BUDGET timeout for a
                // project that actually indexed.
                log.line("ws", "classpath for UNTRACKED key " + cp
                        + " (canon mismatch?) — active=" + activePaths);
            }
        }

        /** Seed an initially-loaded folder so ensureWorkspace() can await it. */
        private void track(WorkspaceFolder f) {
            String cp = canon(f.getUri());
            activePaths.add(cp);
            readyLatch.putIfAbsent(cp, new CountDownLatch(1));
        }

        /** CR-003: block until every initially-seeded workspace's classpath
         *  arrived; log loud for any that didn't (don't fail the whole
         *  harness over one bad initial project — the index watchdog still
         *  exits if NONE index). */
        private void awaitInitialWorkspaces() {
            for (String cp : Set.copyOf(activePaths)) {
                CountDownLatch l = readyLatch.get(cp);
                if (l == null) {
                    continue;
                }
                try {
                    if (!l.await(ENSURE_BUDGET_SECONDS, TimeUnit.SECONDS)) {
                        log.line("ws", "WARNING initial workspace not indexed in "
                                + ENSURE_BUDGET_SECONDS + "s: " + cp);
                    } else {
                        log.line("ws", "initial workspace ready: "
                                + pathToName.getOrDefault(cp, "?") + " (" + cp + ")");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Add a workspace to the running servers and block until its
         *  classpath (hence index) lands. M1: dynamic add only; the
         *  restart-with-union fallback is M1b (built once dynamic add is
         *  empirically settled). */
        String ensureWorkspace(String rawPath) {
            String cp;
            try {
                cp = canon(rawPath);
            } catch (RuntimeException e) {
                return "ERR bad path: " + rawPath;
            }
            if (!Files.isDirectory(Path.of(cp))) {
                return "ERR not a directory: " + cp;
            }
            // CR-006: fail loud rather than silently skip a null server send.
            if (jdtServerRef == null || stsServerRef == null) {
                return "ERR servers not ready (jdt/sts not bound)";
            }
            readyLatch.putIfAbsent(cp, new CountDownLatch(1)); // latch BEFORE notify
            if (activePaths.add(cp)) {
                WorkspaceFolder f = workspaceFolder(Path.of(cp));
                DidChangeWorkspaceFoldersParams pr = new DidChangeWorkspaceFoldersParams(
                        new WorkspaceFoldersChangeEvent(List.of(f), List.of()));
                log.line("ws", "dynamic add: " + cp);
                try {
                    jdtServerRef.getWorkspaceService().didChangeWorkspaceFolders(pr);
                    stsServerRef.getWorkspaceService().didChangeWorkspaceFolders(pr);
                } catch (RuntimeException e) {
                    // Codex#2: a failed send must not poison the path forever
                    // (later calls would hit "already active" + a dead latch).
                    activePaths.remove(cp);
                    readyLatch.remove(cp);
                    return "ERR didChangeWorkspaceFolders failed for " + cp + ": " + e;
                }
            } else {
                log.line("ws", "already active: " + cp);
            }
            try {
                boolean ok = readyLatch.get(cp).await(ENSURE_BUDGET_SECONDS, TimeUnit.SECONDS);
                return ok
                        ? "ready: " + pathToName.getOrDefault(cp, "?") + " (" + cp + ")"
                        : "TIMEOUT: " + cp + " not indexed in " + ENSURE_BUDGET_SECONDS
                          + "s (dynamic add unsupported? — restart-union fallback is M1b)";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "ERR interrupted waiting for " + cp;
            }
        }

        private void startControlServer(int port) throws IOException {
            controlExec = daemonPool("sts5-control");
            control = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            control.setExecutor(controlExec);
            control.createContext("/ensureWorkspace", ex -> {
                String res;
                int code;
                try {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        res = "ERR POST required"; // CR-004: method check
                    } else {
                        // CR-004: bounded read — a path is < 8 KiB; never
                        // readAllBytes() on a long-lived server.
                        byte[] raw = ex.getRequestBody().readNBytes(8192);
                        String body = new String(raw, StandardCharsets.UTF_8).trim();
                        if (body.isEmpty()) {
                            res = "ERR empty body (expected a project path)";
                        } else {
                            res = ensureWorkspace(body);
                        }
                    }
                } catch (Exception e) {
                    res = "ERR " + e;
                }
                code = res.startsWith("ready") ? 200
                        : res.startsWith("TIMEOUT") ? 504
                        : res.startsWith("ERR POST") ? 405
                        : res.startsWith("ERR empty") ? 400 : 500;
                byte[] b = res.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, b.length);
                try (var os = ex.getResponseBody()) {
                    os.write(b);
                }
            });
            control.start();
            log.line("control", "http://127.0.0.1:" + port + "/ensureWorkspace");
        }

    }

    /**
     * Canonicalize a {@code file:} URI or path to one stable key. Must agree
     * across track()/onClasspath()/ensureWorkspace() or a readiness latch
     * never fires (CR-002). Package-private + static for unit testing.
     */
    static String canon(String uriOrPath) {
        String p = uriOrPath;
        if (p.startsWith("file:")) {
            try {
                p = Path.of(URI.create(
                        p.replaceFirst("^file:/+", "file:///"))).toString();
            } catch (RuntimeException e) {
                p = p.replaceFirst("^file:/+", "/");
            }
        }
        try {
            return Path.of(p).toRealPath().toString();
        } catch (IOException e) {
            return Path.of(p).toAbsolutePath().normalize().toString();
        }
    }

    /** Daemon watchdog: STS5's spring/index/updated must arrive, or the
     *  classpath bridge stalled and the MCP endpoint is a healthy-looking
     *  lie. Turn the silent failure into a loud exit (shutdown hook cleans
     *  up). Skipped if teardown already began (clean stop, not a stall). */
    private static void startIndexWatchdog(HarnessLanguageClient stsClient, Log log) {
        Thread t = new Thread(() -> {
            try {
                if (stsClient.awaitIndex(INDEX_WATCHDOG_SECONDS)) {
                    return; // index populated — healthy
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (Shutdown.inProgress()) {
                return;
            }
            log.line("fatal", "no spring/index/updated within "
                    + INDEX_WATCHDOG_SECONDS + "s — classpath bridge stalled; "
                    + "MCP would serve an empty index");
            System.err.println("[sts5-headless] FATAL: classpath bridge stalled "
                    + "(no spring/index/updated in " + INDEX_WATCHDOG_SECONDS + "s)");
            System.exit(2);
        }, "sts5-index-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static ExecutorService daemonPool(String name) {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static Launcher<LanguageServer> lspLauncher(
            LanguageClient client, InputStream in, OutputStream out, ExecutorService ex) {
        return new LSPLauncher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer.class)
                .setInput(in)
                .setOutput(out)
                .setExecutorService(ex)
                .create();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    private static Socket connectWithRetry(int port, Process ls, Log log) throws Exception {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!ls.isAlive()) {
                throw new IllegalStateException("STS5-LS exited (code " + ls.exitValue()
                        + ") before binding the LSP port — check its log");
            }
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_ATTEMPT_MS);
                return s;
            } catch (java.io.IOException notYet) {
                s.close();
                Thread.sleep(CONNECT_BACKOFF_MS);
            }
        }
        throw new IllegalStateException("STS5-LS did not bind 127.0.0.1:" + port
                + " within " + CONNECT_TIMEOUT_MS + "ms — check its log");
    }

    private static Process spawnStsLs(HarnessConfig cfg, int lspPort, Log log)
            throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = List.of(
                javaBin,
                "-Dspring.config.location=classpath:/application.properties",
                "-Dserver.port=" + cfg.mcpPort(),
                "-Dlanguageserver.standalone=true",
                "-Dlanguageserver.standalone-port=" + lspPort,
                "-jar", cfg.lsJar().toString());
        File lsLog = cfg.projectPath().resolve(".sts5-ls.log").toFile();
        log.line("ls", "spawning STS5-LS; stdout/stderr -> " + lsLog);
        return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(lsLog)
                .start();
    }

    private static InitializeParams stsInitParams(List<WorkspaceFolder> folders) {
        InitializeParams p = baseInitParams(folders);
        ClientCapabilities caps = new ClientCapabilities();
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        ws.setConfiguration(true);
        ws.setWorkspaceFolders(true);
        ws.setExecuteCommand(new ExecuteCommandCapabilities(true));
        caps.setWorkspace(ws);
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSemanticTokens(new SemanticTokensCapabilities(false));
        caps.setTextDocument(td);
        p.setCapabilities(caps);
        return p;
    }

    private static InitializeParams jdtInitParams(List<WorkspaceFolder> folders) {
        InitializeParams p = baseInitParams(folders);
        ClientCapabilities caps = new ClientCapabilities();
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        ws.setConfiguration(true);
        ws.setWorkspaceFolders(true);
        ws.setExecuteCommand(new ExecuteCommandCapabilities(true));
        caps.setWorkspace(ws);
        p.setCapabilities(caps);

        Map<String, Object> init = new LinkedHashMap<>();
        init.put("bundles", JdtLs.bundlePaths());
        init.put("workspaceFolders", folders.stream().map(WorkspaceFolder::getUri).toList());
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("import", Map.of("maven", Map.of("enabled", true)));
        java.put("autobuild", Map.of("enabled", true));
        init.put("settings", Map.of("java", java));
        init.put("extendedClientCapabilities", Map.of(
                "classFileContentsSupport", true,
                "shouldLanguageServerExitOnShutdown", true));
        p.setInitializationOptions(init);
        return p;
    }

    private static InitializeParams baseInitParams(List<WorkspaceFolder> folders) {
        InitializeParams p = new InitializeParams();
        p.setProcessId((int) ProcessHandle.current().pid());
        p.setClientInfo(new ClientInfo("sts5-headless", "0.0.1"));
        p.setWorkspaceFolders(folders);
        p.setRootUri(folders.get(0).getUri()); // deprecated; first folder
        return p;
    }

    private static WorkspaceFolder workspaceFolder(Path projectPath) {
        WorkspaceFolder f = new WorkspaceFolder();
        f.setUri(projectPath.toUri().toString());
        f.setName(projectPath.getFileName().toString());
        return f;
    }

    private HarnessMain() {
    }
}
