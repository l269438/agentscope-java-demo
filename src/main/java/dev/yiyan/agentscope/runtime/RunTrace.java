package dev.yiyan.agentscope.runtime;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class RunTrace {
    private final String runId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final Path directory;
    private final String mode;
    private final String scenario;
    private final Map<String, Object> configuration;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private boolean finished;
    private int modelCalls;
    private Consumer<Map<String, Object>> observer;

    public RunTrace(DemoConfig config, String mode, Object input, Object context) {
        this(config, "01-react-ecommerce", mode, input, context);
    }

    public RunTrace(DemoConfig config, String scenario, String mode, Object input, Object context) {
        this.scenario = scenario;
        this.mode = mode;
        this.configuration = config.publicConfiguration();
        this.directory = config.runsDirectory()
                .resolve(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()).resolve(runId);
        try { Files.createDirectories(directory); } catch (IOException e) { throw new UncheckedIOException(e); }
        save("request.json", input);
        save("context.json", context);
        save("metadata.json", metadata("running"));
        workflow("runId=" + runId + "\nprovider=" + configuration.get("provider")
                + " model=" + configuration.get("model") + " dataType=simulated\n"
                + "sdkVersion=" + configuration.get("sdkVersion") + " mode=" + mode + "\n");
        append("request", input, true);
    }

    public synchronized void append(String type, Object payload, boolean console) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", events.size() + 1);
        event.put("time", Instant.now().toString());
        event.put("runId", runId);
        event.put("type", type);
        event.put("payload", payload);
        events.add(event);
        String json = JsonUtils.getJsonCodec().toJson(event);
        String line = "[" + event.get("time") + "] [" + runId + "] [" + type + "] "
                + JsonUtils.getJsonCodec().toJson(payload);
        try {
            Files.writeString(directory.resolve("events.jsonl"), json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(directory.resolve("console.log"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        switch (type) {
            case "request" -> workflow("REQUEST " + JsonUtils.getJsonCodec().toJson(payload));
            case "turn_input" -> workflow("USER_INPUT\n" + JsonUtils.getJsonCodec().toPrettyJson(payload));
            case "application_confirmation", "plan_preview", "reservation_before_confirmation", "reservation_final",
                    "state_directory", "memory_workspace", "context_state", "authorization_boundary", "sandbox_config", "sandbox_host_result" ->
                    workflow(type.toUpperCase() + "\n" + JsonUtils.getJsonCodec().toPrettyJson(payload));
            case "MODEL_CALL_START" -> workflow("MODEL_CALL #" + modelCalls);
            case "REQUIRE_USER_CONFIRM", "USER_CONFIRM_RESULT" -> workflow(type + "\n" + JsonUtils.getJsonCodec().toPrettyJson(payload));
            case "TOOL_RESULT_TEXT_DELTA" -> {
                Map<?, ?> result = JsonUtils.getJsonCodec().convertValue(payload, Map.class);
                workflow("TOOL_RESULT " + result.get("toolCallName") + "\n" + result.get("delta"));
            }
            case "java_tool_input" -> workflow("TOOL\n" + JsonUtils.getJsonCodec().toPrettyJson(payload));
            case "java_tool_output" -> workflow("RESULT\n" + JsonUtils.getJsonCodec().toPrettyJson(payload));
            case "final_reply" -> {
                Map<?, ?> reply = JsonUtils.getJsonCodec().convertValue(payload, Map.class);
                workflow("FINAL_REPLY [" + reply.get("generateReason") + "]\n" + reply.get("text"));
            }
            case "failure", "run_end", "preflight_blocked" -> workflow(type.toUpperCase() + " "
                    + JsonUtils.getJsonCodec().toJson(payload));
            default -> { }
        }
        if (console) System.out.println(line);
        if (observer != null) observer.accept(Map.copyOf(event));
    }

    /** 前端订阅当前运行的新事件，原始持久化仍由append完成。 */
    public synchronized void observe(Consumer<Map<String, Object>> listener) {
        if (finished || observer != null) throw new IllegalStateException("不能重复订阅或订阅已结束运行");
        observer = java.util.Objects.requireNonNull(listener);
    }

    public synchronized void modelCallStarted() { modelCalls++; }
    public synchronized int modelCalls() { return modelCalls; }
    public String runId() { return runId; }
    public Path directory() { return directory; }
    public synchronized List<Map<String, Object>> events() { return List.copyOf(events); }

    public synchronized void finish(String status, Object response) {
        if (finished) throw new IllegalStateException("运行记录已结束，不能覆盖");
        append("run_end", Map.of("status", status, "modelCalls", modelCalls), true);
        save("response.json", response);
        Map<String, Object> metadata = metadata(status);
        metadata.put("finishedAt", Instant.now().toString());
        metadata.put("durationMillis", (System.nanoTime() - startedNanos) / 1_000_000);
        save("metadata.json", metadata);
        finished = true;
    }

    private Map<String, Object> metadata(String status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId);
        metadata.put("scenario", scenario);
        metadata.put("mode", mode);
        metadata.put("status", status);
        metadata.put("startedAt", startedAt.toString());
        metadata.put("configuration", configuration);
        metadata.put("modelCalls", modelCalls);
        metadata.put("billingAmount", "not measured");
        return metadata;
    }

    private void save(String file, Object data) {
        try {
            Files.writeString(directory.resolve(file), JsonUtils.getJsonCodec().toJson(data) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 同步写入实际执行中的关键事件；完整原始事件仍保存在 events.jsonl/console.log。 */
    private void workflow(String text) {
        try {
            Files.writeString(directory.resolve("workflow.log"), text + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
