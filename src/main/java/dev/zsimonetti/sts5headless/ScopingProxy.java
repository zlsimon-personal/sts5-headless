package dev.zsimonetti.sts5headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * M2: workspace-scoping MCP proxy — <b>fail-closed</b>. Fronts STS5's real
 * Streamable-HTTP MCP; the shim points mcp-remote here with an
 * {@code X-STS5-Workspace: <path>} header.
 *
 * <p>Guarantees (a leak here defeats the whole component, so every
 * scope-sensitive path fails closed, never open):
 * <ul>
 *   <li>POST is forwarded with {@code Accept: application/json,
 *       text/event-stream} (Spring AI MCP <i>requires</i> both); the
 *       response is SSE, buffered (it terminates after the response) and
 *       only the JSON-RPC <i>response</i> event is rewritten — progress
 *       events and SSE framing are preserved. GET (the long-lived
 *       server→client SSE) is streamed untouched.</li>
 *   <li>{@code tools/call} requires a resolvable workspace; otherwise an
 *       in-band JSON-RPC error is returned (never STS's unscoped result).</li>
 *   <li>For a tool whose schema has {@code projectName} (learned from a
 *       one-shot {@code tools/list}), the proxy <b>overrides</b> it with the
 *       session's resolved name — a caller cannot escape its workspace.</li>
 *   <li>{@code getBeanDetails} (no {@code projectName}) is post-filtered to
 *       beans whose {@code location.uri} is under the workspace (path
 *       boundary, not string prefix); any shape drift → scoped-empty, not
 *       the whole-workspace union.</li>
 * </ul>
 */
final class ScopingProxy {

    static final String WORKSPACE_HEADER = "X-STS5-Workspace";
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final int stsPort;
    private final Function<String, String> nameForWorkspace; // canon path -> STS name
    private final Log log;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    /** toolName -> inputSchema declares `projectName`. Warmed once, lazily. */
    private final Map<String, Boolean> projectNameTool = new ConcurrentHashMap<>();
    private volatile boolean toolsLearned = false;
    private volatile HttpServer server;

    ScopingProxy(int stsPort, Function<String, String> nameForWorkspace, Log log) {
        this.stsPort = stsPort;
        this.nameForWorkspace = nameForWorkspace;
        this.log = log;
    }

    void start(int port, ExecutorService exec) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(exec);
        server.createContext("/", this::handle);
        server.start();
        log.line("proxy", "scoping MCP proxy http://127.0.0.1:" + port
                + " -> STS :" + stsPort + " (header " + WORKSPACE_HEADER + ")");
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) {
        boolean headersSent = false;
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? ""
                       : "?" + ex.getRequestURI().getRawQuery());

            if ("GET".equals(method)) {
                headersSent = streamPassthrough(ex, path); // server->client SSE
                return;
            }
            if (!"POST".equals(method)) {
                HttpResponse<byte[]> r = forward(ex, method, path, null);
                respond(ex, r, contentType(r), r.body());
                return;
            }

            byte[] reqBody = ex.getRequestBody().readNBytes(MAX_BODY);
            JsonObject rpc = reqJson(reqBody); // client→server is plain JSON
            String rpcMethod = rpc == null ? null : str(rpc, "method");
            JsonElement id = rpc == null ? null : rpc.get("id");

            // Workspace resolution. Blank header is rejected, not treated as cwd.
            String ws = ex.getRequestHeaders().getFirst(WORKSPACE_HEADER);
            boolean haveWs = ws != null && !ws.isBlank();
            String wsName = haveWs ? nameForWorkspace.apply(ws) : null;
            String wsPath = haveWs ? HarnessMain.canon(ws) : null;

            if ("tools/call".equals(rpcMethod)) {
                JsonObject params = rpc.getAsJsonObject("params");
                String tool = params == null ? null : str(params, "name");
                // FAIL CLOSED: a scope-sensitive call without a resolvable
                // workspace must not reach STS's unscoped data.
                if (!haveWs) {
                    sendRpcError(ex, id, "no " + WORKSPACE_HEADER + " header — "
                            + "tools/call is workspace-scoped");
                    return;
                }
                if (wsName == null) {
                    sendRpcError(ex, id, "workspace not registered: " + ws
                            + " (call /ensureWorkspace first)");
                    return;
                }
                ensureToolsLearned();
                if (params != null && tool != null
                        && Boolean.TRUE.equals(projectNameTool.get(tool))) {
                    JsonObject args = params.getAsJsonObject("arguments");
                    if (args == null) {
                        args = new JsonObject();
                        params.add("arguments", args);
                    }
                    // OVERRIDE — do not trust a caller-supplied projectName.
                    args.addProperty("projectName", wsName);
                    reqBody = gson.toJson(rpc).getBytes(StandardCharsets.UTF_8);
                    log.line("proxy", "scoped " + tool + " -> projectName=" + wsName);
                }
                HttpResponse<byte[]> resp = forward(ex, method, path, reqBody);
                byte[] body = resp.body();
                if ("getBeanDetails".equals(tool)) {
                    body = filterBeans(body, wsPath); // SSE-aware, fail-closed
                }
                respond(ex, resp, contentType(resp), body);
                return;
            }

            // Non-scope-sensitive (initialize / notifications / tools/list /
            // ping …): forward as-is, but learn schemas from a tools/list.
            HttpResponse<byte[]> resp = forward(ex, method, path, reqBody);
            if ("tools/list".equals(rpcMethod)) {
                learnTools(resp.body());
            }
            respond(ex, resp, contentType(resp), resp.body());
        } catch (Exception e) {
            if (!headersSent) {
                fail(ex, "proxy error: " + e);
            } else {
                log.line("proxy", "ERROR mid-stream (headers already sent): " + e);
            }
        } finally {
            ex.close();
        }
    }

    // --- forwarding ---------------------------------------------------------

    private HttpResponse<byte[]> forward(HttpExchange ex, String method,
            String path, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder b = baseRequest(ex, method, path, body);
        // Spring AI MCP REQUIRES both media types ("Invalid Accept headers.
        // Expected TEXT_EVENT_STREAM and APPLICATION_JSON") — so a POST
        // response is SSE (one bounded stream that ends after the response;
        // possibly progress events before the result). We buffer it (it
        // terminates) and rewrite the result event in place, preserving the
        // others and SSE framing (CR-003 option b — (a) is impossible here).
        b.setHeader("Accept", "application/json, text/event-stream");
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** GET server→client SSE: stream untouched. @return true (headers sent). */
    private boolean streamPassthrough(HttpExchange ex, String path)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = http.send(
                baseRequest(ex, "GET", path, null).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        copyHeaders(resp, ex, null);
        ex.sendResponseHeaders(resp.statusCode(), 0);
        try (InputStream in = resp.body(); OutputStream out = ex.getResponseBody()) {
            in.transferTo(out);
        } catch (IOException streamAbort) {
            log.line("proxy", "SSE stream aborted: " + streamAbort);
        }
        return true;
    }

    private HttpRequest.Builder baseRequest(HttpExchange ex, String method,
            String path, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + stsPort + path))
                .timeout(Duration.ofSeconds(120))
                .method(method, body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        ex.getRequestHeaders().forEach((k, v) -> {
            String lk = k.toLowerCase();
            if (lk.equals("content-length") || lk.equals("host")
                    || lk.equals("connection") || lk.equals("transfer-encoding")
                    || lk.equals("accept") // we set it deliberately per path
                    || lk.equals(WORKSPACE_HEADER.toLowerCase())) {
                return;
            }
            v.forEach(val -> {
                try {
                    b.header(k, val);
                } catch (IllegalArgumentException ignore) {
                    // restricted header — drop
                }
            });
        });
        return b;
    }

    /** Relay an upstream response, copying its headers (esp. Mcp-Session-Id
     *  — load-bearing for MCP session continuity) with our content-type. */
    private void respond(HttpExchange ex, HttpResponse<?> up, String ctype,
            byte[] body) throws IOException {
        copyHeaders(up, ex, ctype);
        ex.sendResponseHeaders(up.statusCode(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Low-level write with no upstream (proxy-synthesized errors). */
    private void write(HttpExchange ex, int status, String ctype, byte[] body)
            throws IOException {
        if (ctype != null) {
            ex.getResponseHeaders().set("Content-Type", ctype);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void copyHeaders(HttpResponse<?> resp, HttpExchange ex, String ctypeOverride) {
        resp.headers().map().forEach((k, v) -> {
            String lk = k.toLowerCase();
            if (lk.equals("content-length") || lk.equals("transfer-encoding")
                    || lk.equals("connection") || lk.startsWith(":")
                    || (ctypeOverride != null && lk.equals("content-type"))) {
                return;
            }
            v.forEach(val -> ex.getResponseHeaders().add(k, val));
        });
        if (ctypeOverride != null) {
            ex.getResponseHeaders().set("Content-Type", ctypeOverride);
        }
    }

    private String contentType(HttpResponse<?> r) {
        return r.headers().firstValue("content-type").orElse("application/json");
    }

    // --- scoping helpers ----------------------------------------------------

    private void ensureToolsLearned() {
        if (toolsLearned) {
            return;
        }
        synchronized (projectNameTool) {
            if (toolsLearned) {
                return;
            }
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + stsPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":\"proxy-warm\","
                            + "\"method\":\"tools/list\",\"params\":{}}"))
                        .build(), HttpResponse.BodyHandlers.ofByteArray());
                learnTools(r.body());
            } catch (Exception e) {
                log.line("proxy", "tools/list warm failed (will retry): " + e);
            }
        }
    }

    private void learnTools(byte[] body) {
        JsonObject rpc = rpcResponse(body);
        if (rpc == null || !rpc.has("result")
                || !rpc.getAsJsonObject("result").has("tools")) {
            return;
        }
        for (JsonElement te : rpc.getAsJsonObject("result").getAsJsonArray("tools")) {
            JsonObject t = te.getAsJsonObject();
            String name = str(t, "name");
            boolean has = t.has("inputSchema")
                    && t.getAsJsonObject("inputSchema").has("properties")
                    && t.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                        .has("projectName");
            if (name != null) {
                projectNameTool.put(name, has);
            }
        }
        toolsLearned = true;
        log.line("proxy", "learned tool schemas: " + projectNameTool);
    }

    /** Fail-closed: never return STS's unscoped union. Rewrites only the
     *  JSON-RPC response event, preserving SSE framing + any other events. */
    private byte[] filterBeans(byte[] body, String wsPath) {
        JsonObject rpc = rpcResponse(body);
        if (rpc == null || !rpc.has("result")) {
            return body; // no result payload → nothing scope-sensitive to leak
        }
        try {
            JsonArray content = rpc.getAsJsonObject("result").getAsJsonArray("content");
            JsonObject first = content.get(0).getAsJsonObject();
            JsonArray beans = gson.fromJson(first.get("text").getAsString(),
                    JsonArray.class);
            JsonArray kept = new JsonArray();
            for (JsonElement be : beans) {
                JsonObject bean = be.getAsJsonObject();
                JsonObject loc = bean.has("location")
                        ? bean.getAsJsonObject("location") : null;
                String uri = loc != null && loc.has("uri")
                        ? loc.get("uri").getAsString() : null;
                if (uri != null && underWorkspace(HarnessMain.canon(uri), wsPath)) {
                    kept.add(bean);
                }
            }
            first.addProperty("text", gson.toJson(kept));
            log.line("proxy", "getBeanDetails scoped: " + beans.size()
                    + " -> " + kept.size() + " under " + wsPath);
            return reframe(body, rpc);
        } catch (RuntimeException shapeDrift) {
            // FAIL CLOSED: a result we can't filter must not pass through
            // unscoped — substitute an empty scoped result (same framing).
            log.line("proxy", "getBeanDetails shape drift — EMPTY (fail-closed), "
                    + "not the union: " + shapeDrift);
            JsonObject safe = new JsonObject();
            safe.addProperty("jsonrpc", "2.0");
            if (rpc.has("id")) {
                safe.add("id", rpc.get("id"));
            }
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("type", "text");
            item.addProperty("text", "[]");
            content.add(item);
            result.add("content", content);
            result.addProperty("isError", false);
            safe.add("result", result);
            return reframe(body, safe);
        }
    }

    /** Path-boundary containment — not string prefix (so /repo/app does not
     *  match /repo/app2). */
    private static boolean underWorkspace(String p, String ws) {
        return p.equals(ws) || p.startsWith(ws + File.separator);
    }

    // --- SSE / JSON transport handling --------------------------------------

    private boolean isSse(String s) {
        String t = s.stripLeading();
        return !t.startsWith("{") && s.contains("data:");
    }

    /** Split an SSE body into raw event blocks (separated by a blank line). */
    private List<String> sseEvents(String s) {
        List<String> out = new ArrayList<>();
        for (String ev : s.replace("\r\n", "\n").split("\n\n")) {
            if (!ev.isBlank()) {
                out.add(ev);
            }
        }
        return out;
    }

    /** Concatenate an event's {@code data:} lines (SSE allows several). */
    private String eventData(String ev) {
        StringBuilder d = new StringBuilder();
        for (String line : ev.split("\n")) {
            if (line.startsWith("data:")) {
                if (d.length() > 0) {
                    d.append('\n');
                }
                d.append(line.substring(5).stripLeading());
            }
        }
        return d.toString();
    }

    /** Client→server JSON-RPC request is always plain JSON (never SSE). */
    private JsonObject reqJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return gson.fromJson(new String(body, StandardCharsets.UTF_8),
                    JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonObject asObj(String json) {
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            return o != null && o.has("jsonrpc") ? o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The JSON-RPC *response* (has result/error), whether the body is raw
     *  JSON or one/many SSE events (skips progress notifications). */
    private JsonObject rpcResponse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String s = new String(body, StandardCharsets.UTF_8);
        if (!isSse(s)) {
            JsonObject o = asObj(s.trim());
            return o != null && (o.has("result") || o.has("error")) ? o : null;
        }
        for (String ev : sseEvents(s)) {
            JsonObject o = asObj(eventData(ev));
            if (o != null && (o.has("result") || o.has("error"))) {
                return o;
            }
        }
        return null;
    }

    /** Re-emit {@code original}'s transport shape with the JSON-RPC response
     *  swapped for {@code newRpc}; other SSE events pass through verbatim. */
    private byte[] reframe(byte[] original, JsonObject newRpc) {
        String s = new String(original, StandardCharsets.UTF_8);
        String json = gson.toJson(newRpc);
        if (!isSse(s)) {
            return json.getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder();
        for (String ev : sseEvents(s)) {
            JsonObject o = asObj(eventData(ev));
            boolean isResponse = o != null && (o.has("result") || o.has("error"));
            if (isResponse) {
                for (String line : ev.split("\n")) {
                    if (!line.startsWith("data:")) {
                        out.append(line).append('\n'); // keep id:/event: lines
                    }
                }
                out.append("data:").append(json).append("\n\n");
            } else {
                out.append(ev).append("\n\n"); // progress/other — verbatim
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : null;
    }

    private void sendRpcError(HttpExchange ex, JsonElement id, String msg)
            throws IOException {
        log.line("proxy", "REJECT (fail-closed): " + msg);
        JsonObject err = new JsonObject();
        err.addProperty("jsonrpc", "2.0");
        if (id != null) {
            err.add("id", id);
        }
        JsonObject e = new JsonObject();
        e.addProperty("code", -32001);
        e.addProperty("message", "sts5-headless proxy: " + msg);
        err.add("error", e);
        write(ex, 200, "application/json",
                gson.toJson(err).getBytes(StandardCharsets.UTF_8));
    }

    private void fail(HttpExchange ex, String msg) {
        log.line("proxy", "ERROR " + msg);
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(502, b.length);
            ex.getResponseBody().write(b);
        } catch (IOException ignore) {
            // client gone / headers already sent
        }
    }
}
