package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.react.ReactRunner;
import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoServer;
import dev.yiyan.agentscope.runtime.RunTrace;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.util.JsonUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoRuntimeTest {
    @TempDir Path temp;
    @Test void settingsExcludeSecretAndRespectEnvironment() throws Exception {
        Files.writeString(temp.resolve(".env"), "DASHSCOPE_API_KEY=test-secret\nDEMO_PORT=18082\n");
        var config = DemoConfig.load(temp, Map.of("DEMO_PORT", "18083"));
        assertTrue(config.keyConfigured()); assertEquals(18083, config.port());
        assertFalse(config.toString().contains("test-secret"));
        assertFalse(JsonUtils.getJsonCodec().toJson(config.publicConfiguration()).contains("test-secret"));
    }
    @Test void limitsIterationsAndTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new DemoConfig("", 18081, 9, 120, temp));
        assertThrows(IllegalArgumentException.class, () -> new DemoConfig("", 18081, 8, 301, temp));
    }
    @Test void localModelConfigurationNeedsNoCloudKey() throws Exception {
        var config = DemoConfig.load(temp, Map.of("DEMO_PROVIDER", "ollama"));
        assertEquals("ollama", config.provider());
        assertEquals("qwen2.5:0.5b", config.modelName());
        assertEquals("http://127.0.0.1:11434", config.ollamaBaseUrl());
        assertFalse(config.keyConfigured());
        assertTrue(config.modelConfigurationReady());
        assertEquals(8192, config.publicConfiguration().get("contextWindowTokens"));
    }
    @Test void keepsDashScopeDefaultAndSupportsExplicitModel() throws Exception {
        var config = DemoConfig.load(temp, Map.of());
        assertEquals("dashscope", config.provider());
        assertEquals("qwen-plus", config.modelName());
        assertFalse(config.modelConfigurationReady());
        var local = DemoConfig.load(temp, Map.of("DEMO_PROVIDER", "ollama", "DEMO_MODEL", "other-local:4b"));
        assertEquals("other-local:4b", local.modelName());
    }
    @Test void rejectsRemoteOrCredentialBearingLocalEndpoint() {
        for (String url : List.of("https://127.0.0.1:11434", "http://example.com:11434",
                "http://key@127.0.0.1:11434", "http://127.0.0.1:11434/api", "http://127.0.0.1:11434?key=x")) {
            assertThrows(IllegalArgumentException.class, () -> new DemoConfig("ollama", "qwen2.5:0.5b", url, "", 0, 8, 120, temp));
        }
        assertThrows(IllegalArgumentException.class, () -> DemoConfig.load(temp, Map.of("DEMO_PROVIDER", "unknown")));
    }
    @Test void assemblesModelAgentWithoutSendingARequest() {
        var config = new DemoConfig("", 0, 8, 120, temp);
        assertDoesNotThrow(() -> {
            try (var agent = new ReactRunner().createAgent(config, null, new OfflineModel())) {
                assertNotNull(agent);
            }
        });
        assertEquals(1, config.publicConfiguration().get("modelMaxAttempts"));
        assertEquals(0, config.publicConfiguration().get("modelRetries"));
    }
    @Test void tracePreservesInputContextAndResponseWithoutSecret() throws Exception {
        var trace = new RunTrace(new DemoConfig("test-secret", 0, 8, 120, temp), "local-tool-check",
                Map.of("sku", "KB100"), ReactRunner.context());
        trace.append("java_tool_input", Map.of("name", "get_stock", "arguments", Map.of("sku", "KB100")), false);
        trace.append("java_tool_output", Map.of("sku", "KB100", "stock", 12, "inStock", true), false);
        trace.finish("completed", Map.of("stock", 12));
        for (String file : List.of("request.json", "context.json", "metadata.json", "events.jsonl", "console.log", "workflow.log", "response.json")) {
            String text = Files.readString(trace.directory().resolve(file));
            assertFalse(text.contains("test-secret")); assertFalse(text.isBlank());
        }
        String workflow = Files.readString(trace.directory().resolve("workflow.log"));
        assertTrue(workflow.contains("sdkVersion=2.0.3 mode=local-tool-check"));
        assertTrue(workflow.contains("\"stock\" : 12"));
        assertTrue(workflow.contains("\"inStock\" : true"));
        assertEquals(0, trace.modelCalls());
        assertThrows(IllegalStateException.class, () -> trace.finish("changed", Map.of()));
    }
    @Test void healthAndSchemaAreAvailableWithoutKey() throws Exception {
        try (var server = server()) {
            var health = request(server, "GET", "/health", null, "application/json");
            assertEquals(200, health.statusCode()); assertTrue(health.body().contains("\"modelKeyConfigured\":false"));
            var schema = request(server, "GET", "/api/react/tools/schema", null, "application/json");
            assertEquals(200, schema.statusCode()); assertTrue(schema.body().contains("maxPriceYuan"));
        }
    }
    @Test void executesFilterViaHttpAndPersistsSdkResult() throws Exception {
        try (var server = server()) {
            var response = request(server, "POST", "/api/react/tools/run",
                    "{\"name\":\"find_products\",\"arguments\":{\"maxPriceYuan\":200,\"feature\":\"静音\"}}", "application/json");
            assertEquals(200, response.statusCode());
            Map<?, ?> body = json(response.body()); assertEquals(false, body.get("modelInvoked"));
            List<?> products = JsonUtils.getJsonCodec().fromJson((String) body.get("resultText"), List.class);
            assertEquals(List.of("KB100", "KB200"), products.stream().map(p -> ((Map<?, ?>) p).get("sku")).toList());
            var result = (Map<?, ?>) body.get("toolResult");
            assertEquals("find_products", result.get("name")); assertNotNull(result.get("id"));
            try (var files = Files.walk(temp)) {
                Path saved = files.filter(p -> p.getFileName().toString().equals("response.json")).findFirst().orElseThrow();
                assertEquals(json(response.body()), json(Files.readString(saved)));
            }
        }
    }
    @Test void preservesSoldOutResultViaHttp() throws Exception {
        try (var server = server()) {
            var response = request(server, "POST", "/api/react/tools/run",
                    "{\"name\":\"get_stock\",\"arguments\":{\"sku\":\"KB200\"}}", "application/json");
            assertEquals(200, response.statusCode());
            assertEquals(Map.of("sku", "KB200", "stock", 0, "inStock", false), json((String) json(response.body()).get("resultText")));
        }
    }
    @Test void preservesBusinessErrorViaHttp() throws Exception {
        try (var server = server()) {
            var response = request(server, "POST", "/api/react/tools/run",
                    "{\"name\":\"get_stock\",\"arguments\":{\"sku\":\"MISSING\"}}", "application/json");
            assertEquals(200, response.statusCode());
            assertTrue(((String) json(response.body()).get("resultText")).contains("商品不存在"));
            assertEquals(true, json(response.body()).get("toolError"));
        }
    }
    @Test void rejectsUnknownToolAndOverrides() throws Exception {
        try (var server = server()) {
            assertEquals(400, request(server, "POST", "/api/react/tools/run",
                    "{\"name\":\"place_order\",\"arguments\":{}}", "application/json").statusCode());
            assertEquals(400, request(server, "POST", "/api/react/run",
                    "{\"message\":\"键盘\",\"model\":\"other\"}", "application/json").statusCode());
        }
    }
    @Test void blocksModelRunWithoutCredentialAndDoesNotFakeReply() throws Exception {
        try (var server = server()) {
            var response = request(server, "POST", "/api/react/run", "{\"message\":\"200元以内静音键盘\"}", "application/json");
            assertEquals(503, response.statusCode());
            Map<?, ?> body = json(response.body()); assertEquals(false, body.get("modelInvoked")); assertFalse(body.containsKey("reply"));
            try (var files = Files.walk(temp)) {
                var metadata = files.filter(p -> p.getFileName().toString().equals("metadata.json")).findFirst().orElseThrow();
                assertEquals(0, json(Files.readString(metadata)).get("modelCalls"));
                assertEquals("blocked", json(Files.readString(metadata)).get("status"));
            }
        }
    }
    @Test void rejectsWrongMediaTypeAndMalformedJson() throws Exception {
        try (var server = server()) {
            assertEquals(415, request(server, "POST", "/api/react/run", "{}", "text/plain").statusCode());
            assertEquals(415, request(server, "POST", "/api/react/run", "{}", "application/json-evil").statusCode());
            assertEquals(400, request(server, "POST", "/api/react/run", "{", "application/json").statusCode());
            assertEquals(400, request(server, "POST", "/api/react/run", "[]", "application/json").statusCode());
        }
    }
    @Test void limitsBodyAndMessage() throws Exception {
        try (var server = server()) {
            assertEquals(413, request(server, "POST", "/api/react/run", "x".repeat(17000), "application/json").statusCode());
            assertEquals(400, request(server, "POST", "/api/react/run", JsonUtils.getJsonCodec().toJson(Map.of("message", "x".repeat(1501))), "application/json").statusCode());
        }
    }
    @Test void rejectsTrailingObjectsAndTextBeforeModelRun() throws Exception {
        try (var server = server()) {
            for (String body : List.of(
                    "{\"message\":\"预算100元\"}{\"message\":\"预算200元\"}",
                    "{\"message\":\"预算100元\"}  \"message\":\"预算200元\"}",
                    "{\"message\":\"预算100元\"} trailing")) {
                var response = request(server, "POST", "/api/react/run", body, "application/json");
                assertEquals(400, response.statusCode());
                assertEquals("invalid_request", json(response.body()).get("error"));
            }
            try (var files = Files.walk(temp)) {
                assertFalse(files.anyMatch(p -> p.getFileName().toString().equals("metadata.json")));
            }
        }
    }
    private DemoServer server() throws Exception {
        var server = new DemoServer(new DemoConfig("", 0, 8, 120, temp)); server.start(); return server;
    }
    private HttpResponse<String> request(DemoServer server, String method, String path, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (method.equals("GET")) builder.GET();
        else builder.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<?, ?> json(String text) { return JsonUtils.getJsonCodec().fromJson(text, Map.class); }
}
