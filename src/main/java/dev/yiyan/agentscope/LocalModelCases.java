package dev.yiyan.agentscope;

import io.agentscope.core.util.JsonUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 显式执行两次本地模型任务；不由 mvn test 或 LocalSmoke 调用。 */
public final class LocalModelCases {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_PORT", "18181"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("DEMO_PORT 超出允许范围");
        String baseUrl = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<?, ?> configuration = (Map<?, ?>) json(health.body()).get("configuration");
        if (health.statusCode() != 200 || configuration == null || !"ollama".equals(configuration.get("provider"))) {
            throw new IllegalStateException("仅允许 Ollama 本地服务，拒绝向云模型发送请求");
        }
        Path record = Path.of("runs", "model-cases", UUID.randomUUID() + ".json").toAbsolutePath();
        Files.createDirectories(record.getParent());
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("startedAt", Instant.now().toString());
        batch.put("healthResponse", health.body());
        batch.put("configuration", configuration);
        batch.put("attemptsPerCase", 1);
        batch.put("dataType", "simulated");
        List<Map<String, Object>> results = new ArrayList<>();
        batch.put("cases", results);
        String[] inputs = {
                "预算200元，想买适合办公室的静音键盘。只推荐有现货的商品，并说明价格、特点和库存。",
                "预算100元，想买静音键盘，只推荐有现货的商品。"
        };
        for (int i = 0; i < inputs.length; i++) {
            String request = JsonUtils.getJsonCodec().toJson(Map.of("message", inputs[i]));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", i == 0 ? "R01" : "R02");
            entry.put("request", request);
            entry.put("startedAt", Instant.now().toString());
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/react/run"))
                        .timeout(Duration.ofSeconds(130)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                entry.put("httpStatus", response.statusCode());
                entry.put("response", response.body());
                Map<?, ?> body = json(response.body());
                entry.put("runId", body.get("runId"));
                Map<?, ?> reply = body.get("reply") instanceof Map<?, ?> map ? map : Map.of();
                entry.put("transportCompleted", response.statusCode() == 200 && !reply.isEmpty());
                // 工具轨迹与推荐语义另行人工核对，不将 HTTP200 当成业务成功。
                System.out.println(entry.get("caseId") + " HTTP " + response.statusCode() + " runId=" + body.get("runId"));
                System.out.println("reply=" + reply);
            } catch (Exception e) {
                entry.put("transportCompleted", false);
                entry.put("exceptionType", e.getClass().getSimpleName());
                System.out.println(entry.get("caseId") + " " + e.getClass().getSimpleName() + "；不重试");
            }
            entry.put("durationMillis", (System.nanoTime() - start) / 1_000_000);
            entry.put("finishedAt", Instant.now().toString());
            results.add(entry);
        }
        batch.put("finishedAt", Instant.now().toString());
        Files.writeString(record, JsonUtils.getJsonCodec().toJson(batch) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("原始 HTTP 记录：" + record);
    }

    private static Map<?, ?> json(String text) { return JsonUtils.getJsonCodec().fromJson(text, Map.class); }
}
