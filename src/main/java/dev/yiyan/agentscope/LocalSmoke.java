package dev.yiyan.agentscope;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoServer;
import io.agentscope.core.util.JsonUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用实际本地 HTTP 请求检查工具层，固定禁用模型凭据，不读取 .env。 */
public final class LocalSmoke {
    public static void main(String[] args) throws Exception {
        Path records = Path.of("runs").toAbsolutePath();
        DemoConfig config = new DemoConfig("", 0, 8, 120, records);
        List<Map<String, Object>> exchanges = new ArrayList<>();
        try (DemoServer server = new DemoServer(config)) {
            server.start();
            check(server.port(), "GET", "/health", null, 200, "\"modelKeyConfigured\":false", exchanges);
            check(server.port(), "GET", "/api/react/tools/schema", null, 200, "maxPriceYuan", exchanges);
            check(server.port(), "POST", "/api/react/tools/run", Map.of("name", "find_products",
                    "arguments", Map.of("maxPriceYuan", 200, "feature", "静音")), 200, "KB200", exchanges);
            check(server.port(), "POST", "/api/react/tools/run", Map.of("name", "get_stock",
                    "arguments", Map.of("sku", "KB100")), 200, "12", exchanges);
            check(server.port(), "POST", "/api/react/tools/run", Map.of("name", "get_stock",
                    "arguments", Map.of("sku", "KB200")), 200, "false", exchanges);
            check(server.port(), "POST", "/api/react/tools/run", Map.of("name", "find_products",
                    "arguments", Map.of("maxPriceYuan", 100, "feature", "静音")), 200, "[]", exchanges);
            check(server.port(), "POST", "/api/react/tools/run", Map.of("name", "get_stock",
                    "arguments", Map.of("sku", "MISSING")), 200, "商品不存在", exchanges);
            check(server.port(), "POST", "/api/react/run", Map.of("message", "200元以内静音键盘"),
                    503, "model_not_configured", exchanges);
        }
        Files.createDirectories(records.resolve("smoke"));
        Path output = records.resolve("smoke").resolve(UUID.randomUUID() + ".json");
        Files.writeString(output, JsonUtils.getJsonCodec().toJson(Map.of("time", Instant.now().toString(),
                "type", "local-http-smoke", "modelInvoked", false, "checks", exchanges)) + "\n", StandardCharsets.UTF_8);
        System.out.println("LOCAL HTTP SMOKE PASSED: " + exchanges.size() + " checks; modelInvoked=false; " + output);
    }

    private static void check(int port, String method, String path, Object body, int expectedStatus,
                              String expectedText, List<Map<String, Object>> exchanges) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(JsonUtils.getJsonCodec().toJson(body)));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        boolean passed = response.statusCode() == expectedStatus && response.body().contains(expectedText)
                && (!path.equals("/api/react/tools/run") || validToolResult(body, response.body()));
        exchanges.add(Map.of("method", method, "path", path, "request", body == null ? Map.of() : body,
                "status", response.statusCode(), "responseBody", response.body(), "passed", passed));
        if (!passed) throw new IllegalStateException("HTTP check failed: " + path + " status=" + response.statusCode());
    }

    private static boolean validToolResult(Object request, String response) {
        Map<?, ?> input = (Map<?, ?>) request;
        Map<?, ?> args = (Map<?, ?>) input.get("arguments");
        Map<?, ?> envelope = JsonUtils.getJsonCodec().fromJson(response, Map.class);
        if (!Boolean.FALSE.equals(envelope.get("modelInvoked"))) return false;
        String text = (String) envelope.get("resultText");
        if (input.get("name").equals("find_products")) {
            List<?> products = JsonUtils.getJsonCodec().fromJson(text, List.class);
            List<?> skus = products.stream().map(p -> ((Map<?, ?>) p).get("sku")).toList();
            return args.get("maxPriceYuan").equals(200) ? skus.equals(List.of("KB100", "KB200")) : skus.isEmpty();
        }
        if (args.get("sku").equals("MISSING")) return text.contains("商品不存在");
        Map<?, ?> stock = JsonUtils.getJsonCodec().fromJson(text, Map.class);
        return args.get("sku").equals("KB100")
                ? stock.equals(Map.of("sku", "KB100", "stock", 12, "inStock", true))
                : stock.equals(Map.of("sku", "KB200", "stock", 0, "inStock", false));
    }
}
