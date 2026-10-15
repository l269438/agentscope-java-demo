package dev.yiyan.agentscope.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yiyan.agentscope.react.ProductQueryTools;
import dev.yiyan.agentscope.react.ReactRunner;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class DemoServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final ObjectMapper REQUEST_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DemoConfig config;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Semaphore modelSlot = new Semaphore(1);

    public DemoServer(DemoConfig config) throws IOException {
        this.config = config;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("GET") && Set.of("/", "/app.js", "/core.mjs", "/style.css").contains(path)) {
                    serveFrontend(exchange, path);
                } else if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 200, Map.of("status", "ok", "configuration", config.publicConfiguration()));
                } else if (path.equals("/api/react/catalog") && exchange.getRequestMethod().equals("GET")) {
                    var data = ProductQueryTools.dataset();
                    send(exchange, 200, Map.of("catalog", data.get("catalog"), "revision", data.get("revision"), "dataType", "simulated"));
                } else if (path.equals("/api/react/tools/schema") && exchange.getRequestMethod().equals("GET")) {
                    Toolkit toolkit = toolkit(null);
                    send(exchange, 200, Map.of("tools", toolkit.getToolSchemas(), "dataType", "simulated"));
                } else if (path.equals("/api/react/tools/run") && exchange.getRequestMethod().equals("POST")) {
                    toolCheck(exchange, read(exchange));
                } else if (path.equals("/api/react/run") && exchange.getRequestMethod().equals("POST")) {
                    modelRun(exchange, read(exchange), false);
                } else if (path.equals("/api/react/chat") && exchange.getRequestMethod().equals("POST")) {
                    modelRun(exchange, read(exchange), true);
                } else {
                    send(exchange, 404, Map.of("error", "not_found", "message", "请求路径或方法不匹配"));
                }
            } catch (BadRequest e) {
                send(exchange, e.status, Map.of("error", "invalid_request", "message", e.getMessage()));
            } catch (RuntimeException e) {
                // 不输出异常详情，避免提供商异常中出现凭据或请求头。
                send(exchange, 500, Map.of("error", "internal_error", "exceptionType", e.getClass().getSimpleName()));
            }
        }
    }

    private void toolCheck(HttpExchange exchange, Map<String, Object> input) throws IOException {
        if (!input.keySet().equals(Set.of("name", "arguments"))
                || !(input.get("name") instanceof String name)
                || !Set.of("find_products", "get_stock").contains(name)
                || !(input.get("arguments") instanceof Map<?, ?>)) {
            throw new BadRequest(400, "需要指定 find_products/get_stock 以及 arguments 对象");
        }
        RunTrace trace = new RunTrace(config, "local-tool-check", input, ReactRunner.context());
        try {
            Map<String, Object> arguments = castMap(input.get("arguments"));
            String callId = UUID.randomUUID().toString();
            ToolUseBlock use = ToolUseBlock.builder().id(callId).name(name).input(arguments)
                    .content(JsonUtils.getJsonCodec().toJson(arguments)).build();
            trace.append("tool_use", use, true);
            var results = toolkit(trace).callTools(List.of(use), null, null, null).block(Duration.ofSeconds(10));
            if (results == null || results.isEmpty()) throw new IllegalStateException("工具结果为空");
            var result = results.get(0);
            trace.append("tool_result", result, true);
            String text = result.getOutput().stream().filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("runId", trace.runId());
            response.put("mode", "local-tool-check");
            response.put("modelInvoked", false);
            response.put("toolResult", result);
            response.put("resultText", text);
            boolean toolError = result.getState() != null && result.getState().toString().equalsIgnoreCase("error");
            response.put("toolError", toolError);
            trace.finish(toolError ? "tool_error" : "completed", response);
            send(exchange, 200, response);
        } catch (RuntimeException e) {
            Map<String, Object> error = Map.of("runId", trace.runId(), "mode", "local-tool-check",
                    "modelInvoked", false, "error", "tool_check_failed",
                    "exceptionType", e.getClass().getSimpleName());
            trace.finish("failed", error);
            send(exchange, 500, error);
        }
    }

    private void modelRun(HttpExchange exchange, Map<String, Object> input, boolean streaming) throws IOException {
        if (!input.keySet().equals(Set.of("message")) || !(input.get("message") instanceof String message)
                || message.isBlank() || message.length() > 1500) {
            throw new BadRequest(400, "message 必须是 1 至 1500 字符的非空字符串");
        }
        if (!modelSlot.tryAcquire()) {
            send(exchange, 429, Map.of("error", "model_busy", "message", "当前已有模型任务，请等待结束"));
            return;
        }
        try {
            RunTrace trace = new RunTrace(config, "model-run", input, ReactRunner.context());
            if (!config.modelConfigurationReady()) {
                Map<String, Object> error = Map.of("runId", trace.runId(), "error", "model_not_configured",
                        "modelInvoked", false, "message", "在本机环境或项目 .env 中配置 DASHSCOPE_API_KEY 后重启服务");
                trace.append("preflight_blocked", Map.of("reason", "missing_model_key"), true);
                trace.finish("blocked", error);
                send(exchange, 503, error);
                return;
            }
            FrontendEventStream stream = null;
            if (streaming) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                try {
                    exchange.sendResponseHeaders(200, 0);
                } catch (IOException e) {
                    trace.finish("client_disconnected", Map.of("runId", trace.runId(), "modelInvoked", false));
                    throw e;
                }
                stream = new FrontendEventStream(exchange.getResponseBody());
                stream.send("run", Map.of("runId", trace.runId(), "configuration", config.publicConfiguration()));
                trace.observe(stream::accept);
            }
            try {
                var reply = new ReactRunner().run(config, message, trace);
                Map<String, Object> response = Map.of("runId", trace.runId(), "mode", "model-run",
                        "provider", config.provider(), "model", config.modelName(), "reply", reply, "modelCalls", trace.modelCalls(),
                        "dataType", "simulated", "events", trace.events());
                trace.finish(reply.stageSummary() ? "max_iterations" : "completed", response);
                if (stream != null) {
                    stream.send("done", Map.of("runId", trace.runId(), "modelCalls", trace.modelCalls(),
                            "reply", reply, "model", config.modelName(), "provider", config.provider(), "dataType", "simulated"));
                } else send(exchange, 200, response);
            } catch (RuntimeException e) {
                Map<String, Object> error = Map.of("runId", trace.runId(), "error", "model_run_failed",
                        "modelInvoked", trace.modelCalls() > 0, "modelCalls", trace.modelCalls(),
                        "exceptionType", e.getClass().getSimpleName(),
                        "message", "请检查模型配置或提供商状态；已保存执行到失败处的事件，未自动重跑");
                trace.append("failure", error, true);
                trace.finish("failed", error);
                if (stream != null) stream.send("error", error);
                else send(exchange, 502, error);
            }
        } finally { modelSlot.release(); }
    }

    private Toolkit toolkit(RunTrace trace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProductQueryTools(trace));
        return toolkit;
    }

    private static void serveFrontend(HttpExchange exchange, String path) throws IOException {
        String file = path.equals("/") ? "index.html" : path.substring(1);
        try (var input = DemoServer.class.getResourceAsStream("/web/" + file)) {
            if (input == null) {
                send(exchange, 404, Map.of("error", "frontend_not_built"));
                return;
            }
            String type = file.endsWith(".html") ? "text/html" : file.endsWith(".css") ? "text/css" : "text/javascript";
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", type + "; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            byte[] bytes = input.readAllBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Map<String, Object> read(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new BadRequest(415, "Content-Type 需为 application/json");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new BadRequest(413, "请求体超过 16 KiB");
        try {
            Map<String, Object> input = castMap(REQUEST_JSON.readValue(bytes, Map.class));
            if (input == null) throw new IllegalArgumentException();
            return input;
        } catch (IOException | RuntimeException e) {
            throw new BadRequest(400, "请求体需为一个完整 JSON 对象，不能含尾随内容");
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object map) { return (Map<String, Object>) map; }
    private static void send(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonUtils.getJsonCodec().toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private static final class BadRequest extends RuntimeException {
        private final int status;
        BadRequest(int status, String message) { super(message); this.status = status; }
    }
}
