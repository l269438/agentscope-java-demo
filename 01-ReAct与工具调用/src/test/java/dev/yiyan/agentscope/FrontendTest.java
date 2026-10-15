package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoServer;
import dev.yiyan.agentscope.runtime.FrontendEventStream;
import dev.yiyan.agentscope.runtime.RunTrace;
import io.agentscope.core.util.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 资源、协议及日志观察器检查，无模型客户端或模型请求。 */
class FrontendTest {
    @TempDir Path temp;

    @Test void servesSameOriginAssetsWithRestrictedPolicy() throws Exception {
        try (var server = server()) {
            for (String path : List.of("/", "/app.js", "/core.mjs", "/style.css")) {
                var response = request(server, path, null);
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
                assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("connect-src 'self'"));
            }
            assertTrue(request(server, "/", null).body().contains("模拟商品"));
            assertTrue(request(server, "/app.js", null).body().contains("fetch('/api/react/chat'"));
            for (String path : List.of("/runs/example/response.json", "/web/../../pom.xml", "/.env")) {
                assertEquals(404, request(server, path, null).statusCode());
            }
        }
    }

    @Test void catalogDoesNotPretendInventoryWasQueried() throws Exception {
        try (var server = server()) {
            var response = request(server, "/api/react/catalog", null);
            assertEquals(200, response.statusCode());
            Map<?, ?> data = json(response.body());
            assertEquals("simulated", data.get("dataType"));
            assertEquals("keyboards-v1", data.get("revision"));
            assertEquals(3, ((List<?>) data.get("catalog")).size());
            assertFalse(data.containsKey("stock"));
            assertFalse(response.body().contains("inStock"));
        }
    }

    @Test void chatBlocksMissingKeyBeforeStreamingAndKeepsRawRun() throws Exception {
        try (var server = server()) {
            var response = request(server, "/api/react/chat", "{\"message\":\"200元静音键盘\"}");
            assertEquals(503, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            var data = json(response.body());
            assertEquals(false, data.get("modelInvoked"));
            assertFalse(data.containsKey("reply"));
            try (var files = Files.walk(temp)) {
                var metadata = files.filter(p -> p.getFileName().toString().equals("metadata.json")).findFirst().orElseThrow();
                assertEquals(0, json(Files.readString(metadata)).get("modelCalls"));
                assertEquals("blocked", json(Files.readString(metadata)).get("status"));
            }
        }
    }

    @Test void chatRejectsOverridesAndTrailingJsonWithoutStartingRun() throws Exception {
        try (var server = server()) {
            for (String body : List.of("{\"message\":\"键盘\",\"model\":\"other\"}",
                    "{\"message\":\"键盘\"}{}", "{\"message\":\"\"}")) {
                assertEquals(400, request(server, "/api/react/chat", body).statusCode());
            }
            try (var files = Files.walk(temp)) {
                assertFalse(files.anyMatch(p -> p.getFileName().toString().equals("metadata.json")));
            }
        }
    }

    @Test void framesContainOnlyAllowedEventsAndEscapeNewlines() {
        var bytes = new ByteArrayOutputStream();
        var stream = new FrontendEventStream(bytes);
        stream.accept(Map.of("type", "THINKING_DELTA", "payload", "do-not-share"));
        stream.accept(Map.of("type", "request", "payload", "also-hidden"));
        stream.send("run", Map.of("runId", "unit-fixture"));
        stream.accept(Map.of("type", "java_tool_output", "payload", Map.of("name", "get_stock", "result", Map.of("sku", "KB100", "stock", 12))));
        stream.send("done", Map.of("text", "第一行\n第二行"));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("do-not-share")); assertFalse(text.contains("also-hidden"));
        assertTrue(text.contains("event: trace\ndata: "));
        assertTrue(text.contains("\"stock\":12"));
        assertTrue(text.contains("第一行\\n第二行"));
        assertEquals(3, text.split("\n\n").length);
        assertThrows(IllegalArgumentException.class, () -> stream.send("bad\nevent", Map.of()));
    }

    @Test void observersReceiveNewImmutableEventsWithoutChangingRecord() throws Exception {
        var trace = new RunTrace(new DemoConfig("", 0, 8, 120, temp), "local-tool-check", Map.of("message", "unit-fixture"), Map.of());
        var received = new ArrayList<Map<String, Object>>();
        trace.observe(received::add);
        trace.append("java_tool_output", Map.of("name", "get_stock", "result", Map.of("stock", 12)), false);
        assertEquals(1, received.size());
        assertEquals(trace.events().get(1), received.get(0));
        assertThrows(UnsupportedOperationException.class, () -> received.get(0).put("type", "fake"));
        assertThrows(IllegalStateException.class, () -> trace.observe(received::add));
        trace.finish("completed", Map.of("modelInvoked", false));
        assertTrue(Files.readString(trace.directory().resolve("events.jsonl")).contains("java_tool_output"));
        assertEquals(0, trace.modelCalls());
    }

    @Test void disconnectedBrowserDoesNotInterruptRawPersistence() throws Exception {
        var broken = new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("disconnected"); } };
        var stream = new FrontendEventStream(broken);
        var trace = new RunTrace(new DemoConfig("", 0, 8, 120, temp), "local-tool-check", Map.of(), Map.of());
        trace.observe(stream::accept);
        assertDoesNotThrow(() -> {
            trace.append("java_tool_output", Map.of("name", "get_stock", "result", Map.of("stock", 0)), false);
            trace.finish("completed", Map.of("modelInvoked", false));
        });
        assertFalse(stream.connected());
        assertEquals("completed", json(Files.readString(trace.directory().resolve("metadata.json"))).get("status"));
    }

    private DemoServer server() throws Exception {
        var server = new DemoServer(new DemoConfig("", 0, 8, 120, temp)); server.start(); return server;
    }
    private HttpResponse<String> request(DemoServer server, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<?, ?> json(String text) { return JsonUtils.getJsonCodec().fromJson(text, Map.class); }
}
