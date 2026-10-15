package dev.yiyan.agentscope;

import dev.yiyan.agentscope.context.ContextDemo;
import dev.yiyan.agentscope.memory.FileMemoryDemo;
import dev.yiyan.agentscope.observe.EventView;
import dev.yiyan.agentscope.observe.ObserveDemo;
import dev.yiyan.agentscope.observe.ObserveMiddleware;
import dev.yiyan.agentscope.permission.ConfirmationGate;
import dev.yiyan.agentscope.permission.SelectionTools;
import dev.yiyan.agentscope.permission.PermissionDemo;
import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.runtime.RunTrace;
import dev.yiyan.agentscope.sandbox.SandboxDemo;
import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.skill.SkillDemo;
import dev.yiyan.agentscope.subagent.PlanApprovalGate;
import dev.yiyan.agentscope.subagent.SubagentDemo;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/** 显式、单案例、本机模型取材；不自动重试，不启动子Agent，不修改旧run。 */
public final class LocalSeriesCases {
    private final Path project;
    private final DemoConfig config;
    private final RunTrace trace;
    private final Path artifacts;
    private final List<Map<String, Object>> replies = new ArrayList<>();
    private int summaryCalls;

    private LocalSeriesCases(Path project, DemoConfig config, String scenario, String[] args) throws Exception {
        this.project = project; this.config = config;
        trace = new RunTrace(config, scenario, "local-series-model-run", Map.of("arguments", List.of(args)),
                Map.of("runner", "LocalSeriesCases", "fixtureRoot", project.resolve("fixtures").toString(),
                        "promptRevision", "ecommerce-v1", "spawnedSubagents", 0,
                        "notes", "实际模型输入在model_input事件；模拟资料；每案例显式执行一次"));
        artifacts = trace.directory().resolve("artifacts"); Files.createDirectories(artifacts);
    }

    private Model model(String purpose) {
        Model delegate = DemoModels.create(config);
        return new Model() {
            @Override public String getModelName() { return delegate.getModelName(); }
            @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    if (trace.modelCalls() >= 16) return Flux.error(new IllegalStateException("单案例模型调用已达16次上限"));
                    trace.modelCallStarted();
                    if (purpose.equals("summary")) summaryCalls++;
                    // 仅保存可见文本/动作/工具结果；不保存模型思考块或凭据。
                    List<Map<String, Object>> inputs = messages.stream().map(m -> {
                        Map<String, Object> safe = new LinkedHashMap<>(); safe.put("role", m.getRole()); safe.put("name", m.getName());
                        safe.put("content", m.getContent().stream().filter(b -> b instanceof TextBlock
                                || b instanceof ToolUseBlock || b instanceof ToolResultBlock).toList()); return safe;
                    }).toList();
                    trace.append("model_input", Map.of("purpose", purpose, "call", trace.modelCalls(),
                            "messages", inputs, "tools", tools == null ? List.of() : tools), false);
                    trace.append("MODEL_CALL_START", Map.of("purpose", purpose, "call", trace.modelCalls()), true);
                    return delegate.stream(messages, tools, options);
                });
            }
        };
    }

    private Msg turn(Flux<AgentEvent> stream, Object message, RuntimeContext ctx) {
        trace.append("turn_input", Map.of("message", message, "userId", ctx.getUserId(), "sessionId", ctx.getSessionId()), true);
        var result = new AtomicReference<Msg>();
        stream.doOnNext(event -> {
            String type = event.getType().name();
            if (type.startsWith("THINKING_") || type.equals("MODEL_CALL_START")) return;
            if (event instanceof AgentResultEvent done && event.getSource() == null) {
                result.set(done.getResult()); return;
            }
            trace.append(type, event, !(event instanceof TextBlockDeltaEvent) && !type.equals("TOOL_CALL_DELTA"));
        }).timeout(Duration.ofSeconds(config.timeoutSeconds())).blockLast();
        Msg reply = result.get(); require(reply != null, "未收到最终消息");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", reply.getTextContent()); output.put("generateReason", reply.getGenerateReason());
        output.put("toolCalls", reply.getContentBlocks(ToolUseBlock.class));
        replies.add(output); trace.append("final_reply", output, true); return reply;
    }

    private void permission(String scenario) {
        Clock clock = Clock.system(SelectionTools.ZONE); var tools = new SelectionTools();
        var ctx = RuntimeContext.builder().userId("alice").sessionId("permission-" + UUID.randomUUID()).build();
        boolean disabled = scenario.equals("02-maintenance");
        String input = "把KB100保存到我的模拟选购清单，备注：办公备选。";
        try (var agent = PermissionDemo.create(model("primary"), tools, disabled)) {
            Msg reply = turn(agent.streamEvents(input, ctx), input, ctx);
            trace.append("selection_before_confirmation", Map.of("count", tools.selectionCount()), true);
            require(tools.selectionCount() == 0, "确认前出现选购清单");
            if (disabled) {
                require(reply.getGenerateReason() != GenerateReason.PERMISSION_ASKING, "DENY不应进入ASK");
            } else {
                require(reply.getGenerateReason() == GenerateReason.PERMISSION_ASKING, "模型未产生待确认保存");
                var gate = new ConfirmationGate(ctx, clock); var preview = gate.capture(reply);
                boolean approved = scenario.equals("02-approve");
                trace.append("application_confirmation", Map.of("approved", approved, "originalOperations", preview.operations()), true);
                Msg confirmation = gate.confirmationMessage(ctx, preview.ticket(), approved);
                turn(agent.streamEvents(List.of(confirmation), ctx), confirmation.getTextContent(), ctx);
                require(tools.selectionCount() == (approved ? 1 : 0), "批准/拒绝后的清单数量不符");
            }
            trace.append("selection_final", Map.of("count", tools.selectionCount(), "savingDisabled", disabled), true);
        }
    }

    private void workspace() {
        var ctx = SessionStateDemo.context("demo-user", "workspace-" + UUID.randomUUID());
        try (var agent = dev.yiyan.agentscope.workspace.WorkspaceDemo.create(model("primary"), project.resolve("fixtures/workspace-03"))) {
            String input = dev.yiyan.agentscope.workspace.WorkspaceDemo.DEFAULT_MESSAGE;
            turn(agent.streamEvents(input, ctx), input, ctx);
        }
    }

    private Path previousArtifact(String runId, String name) throws Exception {
        require(runId.matches("[0-9a-f-]{36}"), "前次runId格式无效");
        Path root = config.runsDirectory().resolve(LocalDate.now(SelectionTools.ZONE).toString()).resolve(runId).resolve("artifacts").resolve(name);
        require(Files.isDirectory(root), "前次资料目录不存在");
        Path continuation = artifacts.resolve(name);
        // 保留前次运行资料。新的调用在逐字复制的工作目录内继续写状态。
        try (var paths = Files.walk(root)) {
            for (Path source : paths.toList()) {
                require(!Files.isSymbolicLink(source), "演示资料不接受符号链接");
                Path target = continuation.resolve(root.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target); else Files.copy(source, target);
            }
        }
        trace.append("continuation_copy", Map.of("source", root.toString(), "destination", continuation.toString()), true);
        return continuation;
    }

    private void session(boolean resume, String[] args) throws Exception {
        Path states = resume ? previousArtifact(args[1], "states") : artifacts.resolve("states");
        var ctx = SessionStateDemo.context("demo-user", "series-selection-04");
        String input = resume ? "我的预算是多少？在前面的库存快照中，符合偏好的现货商品是哪款？" : "请记住本会话的模拟选购资料：预算200元，偏好静音无线办公键盘；KB100静音无线键盘149元，库存快照12件；KB200轻薄无线键盘199元，库存快照0件；KB300机械键盘299元，库存快照8件。请简要确认。";
        trace.append("state_directory", Map.of("path", states.toString(), "newJvmContinuation", resume), true);
        try (var agent = SessionStateDemo.create(model("primary"), states)) { turn(agent.streamEvents(input, ctx), input, ctx); }
    }

    private void memory(boolean read, String[] args) throws Exception {
        Path root = read ? previousArtifact(args[1], "workspace") : artifacts.resolve("workspace");
        var ctx = SessionStateDemo.context("demo-user", (read ? "memory-new-" : "memory-save-") + UUID.randomUUID());
        String input = read ? "请查询记忆中的选购偏好，再根据本轮模拟快照给建议：预算200元；KB100静音无线办公键盘149元、库存12；KB200轻薄静音无线办公键盘199元、库存0；KB300机械有线游戏键盘299元、库存8。按记忆中的字段输出并标来源：本轮模拟快照。"
                : "请用memory_save保存这条选购偏好，只保存冒号后的条目：办公键盘优先静音无线；推荐按商品、价格、库存快照、理由、来源排列；缺失项标待确认。";
        trace.append("memory_workspace", Map.of("path", root.toString(), "newSession", read), true);
        try (var agent = FileMemoryDemo.create(model("primary"), root)) {
            if (!read && args[0].equals("05-save-v3")) {
                String approved = "- 办公键盘优先静音无线；推荐按商品、价格、库存快照、理由、来源排列；缺失项标待确认。";
                FileMemoryDemo.guardModelWrites(agent, ctx, approved);
                trace.append("backend_approved_memory", Map.of("content", approved, "boundUser", ctx.getUserId(), "boundSession", ctx.getSessionId()), true);
            }
            Msg reply = turn(agent.streamEvents(input, ctx), input, ctx);
            if (args[0].equals("05-save-v3")) {
                String saved = agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, "MEMORY.md");
                require(saved.contains("办公键盘优先静音无线") && !saved.contains("授权"), "未仅保存批准的项目约定");
                trace.append("approved_memory_file", Map.of("content", saved), true);
            }
            if (args[0].equals("05-read-v3")) require(reply.getTextContent().contains("KB100")
                    && reply.getTextContent().contains("149") && reply.getTextContent().contains("12"), "新会话未按偏好推荐本轮商品");
        }
    }

    private void context(boolean guided) throws Exception {
        var ctx = SessionStateDemo.context("demo-user", "context-" + UUID.randomUUID());
        try (var agent = ContextDemo.create(model("primary"), model("summary"), artifacts.resolve("workspace"), artifacts.resolve("states"))) {
            for (String input : List.of(guided ? ContextDemo.GUIDED_MESSAGE : "查询长篇模拟商品选购报告，推荐200元以内的静音现货键盘并说明排除理由。",
                    "按推荐和排除分组，保留价格与库存快照。", "只列KB100，并核对价格与库存快照。")) {
                turn(agent.streamEvents(input, ctx), input, ctx);
            }
            trace.append("context_state", Map.of("summaryCalls", summaryCalls,
                    "messages", agent.getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId()).getContext().size()), true);
        }
    }

    private void skill() throws Exception {
        Path root = artifacts.resolve("workspace"); SkillDemo.install(project.resolve("fixtures/skills-07"), root);
        var ctx = SessionStateDemo.context("demo-user", "skill-" + UUID.randomUUID());
        try (var agent = SkillDemo.create(model("primary"), root)) {
            String input = "按商品选购技能推荐200元以内的静音现货键盘，保留价格、库存快照、理由和来源。";
            turn(agent.streamEvents(input, ctx), input, ctx);
        }
    }

    private void planOnly() throws Exception {
        var ctx = SubagentDemo.context("demo-user", "plan-" + UUID.randomUUID());
        Path root = artifacts.resolve("workspace"); Files.createDirectories(root);
        try (var agent = SubagentDemo.create(model("primary"), root, ctx.getSessionId())) {
            // 没有子Agent授权：只暴露商品/计划工具，从目录中移除所有委派入口。
            for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
                if (!Set.of("product_report", "plan_enter", "plan_write", "plan_exit").contains(name)) agent.getToolkit().removeTool(name);
            }
            agent.enterPlanMode(ctx);
            String input = "阅读模拟商品，写出product-finder筛选和stock-reviewer库存复核计划，再用plan_exit请求批准。只写计划，不启动子Agent，不执行计划，等待用户确认。";
            Msg reply = turn(agent.streamEvents(input, ctx), input, ctx);
            boolean pending = reply.getGenerateReason() == GenerateReason.PERMISSION_ASKING;
            if (pending) {
                var gate = new PlanApprovalGate(ctx, Clock.systemUTC());
                trace.append("plan_preview", gate.capture(reply), true);
            }
            trace.append("authorization_boundary", Map.of("spawnedSubagents", 0, "planApprovalPending", pending), true);
            require(pending, "模型未通过plan_exit请求确认");
            String path = agent.getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId()).getPlanModeContext().getCurrentPlanFile();
            require(path != null && !path.isBlank(), "没有计划文件引用");
            String content = agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, path);
            require(content != null && !content.isBlank(), "计划文件为空");
            trace.append("plan_file", Map.of("path", path, "content", content), true);
        }
    }

    private void observe() {
        var ctx = ObserveDemo.context(UUID.randomUUID().toString(), true);
        var middleware = new ObserveMiddleware(); var view = new EventView(); var tools = new ObserveDemo.ProductTools(false);
        try (var agent = ObserveDemo.create(model("primary"), tools, middleware)) {
            String input = "推荐200元以内的静音现货键盘，并说明排除理由。";
            turn(agent.streamEvents(input, ctx).doOnNext(view::accept), input, ctx);
            trace.append("event_view", Map.of("eventTypes", view.lines(), "ended", view.ended(),
                    "tools", view.tools().entrySet().stream().map(e -> Map.of("key", e.getKey(), "progress", e.getValue())).toList(),
                    "readCount", tools.reads.get()), true);
            trace.append("middleware_entries", middleware.entries(), true);
        }
    }

    private void sandbox(String image) throws Exception {
        Path root = artifacts.resolve("host-workspace"); SandboxDemo.install(project.resolve("fixtures/sandbox-10"), root);
        var client = new dev.yiyan.agentscope.sandbox.SandboxDockerCheck.TrackingClient();
        var fs = SandboxDemo.filesystem(image, artifacts.resolve("snapshots"), new WorkspaceSpec(), client);
        var ctx = SandboxDemo.context("demo-user", UUID.randomUUID().toString());
        trace.append("sandbox_config", Map.of("image", image, "command", SandboxDemo.COMMAND, "network", "none", "hostBindMounts", false), true);
        var inspect = new io.agentscope.core.middleware.MiddlewareBase() {
            @Override public Flux<AgentEvent> onModelCall(io.agentscope.core.agent.Agent agent, RuntimeContext context,
                    io.agentscope.core.middleware.ModelCallInput input,
                    java.util.function.Function<io.agentscope.core.middleware.ModelCallInput, Flux<AgentEvent>> next) {
                try {
                    var state = (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState)
                            client.opened.get(client.opened.size() - 1).getState();
                    var check = dev.yiyan.agentscope.sandbox.SandboxDockerCheck.docker("inspect", state.getContainerId(), "--format",
                            "network={{.HostConfig.NetworkMode}}; memory={{.HostConfig.Memory}}; cpu={{.HostConfig.NanoCpus}}; readonly={{.HostConfig.ReadonlyRootfs}}; mounts={{len .Mounts}}; user={{.Config.User}}; pids={{.HostConfig.PidsLimit}}; caps={{json .HostConfig.CapDrop}}; security={{json .HostConfig.SecurityOpt}}");
                    require(check.code() == 0, "无法检查本案例容器");
                    String value = check.text().strip();
                    trace.append("sandbox_inspect", Map.of("containerId", state.getContainerId(), "configuration", value), true);
                    require(value.contains("network=none; memory=268435456; cpu=1000000000; readonly=true; mounts=0; user=65534:65534; pids=64")
                            && value.contains("ALL") && value.contains("no-new-privileges"), "容器配置不匹配");
                } catch (Exception failure) { return Flux.error(failure); }
                return next.apply(input);
            }
        };
        try (var agent = SandboxDemo.create(model("primary"), root, fs, inspect)) {
            String input = "处理模拟商品CSV，展示200元以内的静音现货键盘选购预览。";
            turn(agent.streamEvents(input, ctx), input, ctx);
        } finally {
            for (var sandbox : client.opened) {
                var state = (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState) sandbox.getState();
                boolean remains = dev.yiyan.agentscope.sandbox.SandboxDockerCheck.docker("inspect", state.getContainerId()).code() == 0;
                trace.append("sandbox_cleanup", Map.of("containerId", state.getContainerId(), "remainedAfterCall", remains), true);
                if (remains) sandbox.shutdown(); // 仅处理本案例创建的容器；不操作已有容器。
                require(dev.yiyan.agentscope.sandbox.SandboxDockerCheck.docker("inspect", state.getContainerId()).code() != 0,
                        "本案例容器未释放");
            }
        }
        require(!Files.exists(root.resolve("selection-preview.md")), "宿主不应产生预览文件");
        trace.append("sandbox_host_result", Map.of("previewWrittenOnHost", false, "snapshots", artifacts.resolve("snapshots").toString()), true);
    }

    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    public static void main(String[] args) throws Exception {
        Set<String> allowed = Set.of("02-approve", "02-reject", "02-maintenance", "03", "04-seed", "04-resume",
                "05-save", "05-read", "05-save-v2", "05-read-v2", "05-save-v3", "05-read-v3", "06", "06-v2", "06-v3", "06-guided", "07", "08-plan", "09", "10");
        require(args.length >= 1 && allowed.contains(args[0]), "必须显式指定单个案例；不支持自动批量运行");
        require((Set.of("04-resume", "05-read", "05-read-v2", "05-read-v3", "10").contains(args[0]) ? 2 : 1) == args.length, "案例参数数量不符");
        Path project = Path.of("").toAbsolutePath(); var config = DemoConfig.load(project, System.getenv());
        require(config.provider().equals("ollama") && config.modelName().equals("qwen3:4b-instruct"), "仅使用已授权的本机qwen3:4b-instruct");
        var run = new LocalSeriesCases(project, config, args[0], args);
        try {
            switch (args[0]) {
                case "02-approve", "02-reject", "02-maintenance" -> run.permission(args[0]);
                case "03" -> run.workspace();
                case "04-seed", "04-resume" -> run.session(args[0].equals("04-resume"), args);
                case "05-save", "05-read", "05-save-v2", "05-read-v2", "05-save-v3", "05-read-v3" -> run.memory(args[0].startsWith("05-read"), args);
                case "06", "06-v2", "06-v3", "06-guided" -> run.context(args[0].equals("06-guided")); case "07" -> run.skill(); case "08-plan" -> run.planOnly();
                case "09" -> run.observe(); case "10" -> run.sandbox(args[1]);
                default -> throw new IllegalArgumentException("未知案例");
            }
            run.trace.finish("completed", Map.of("replies", run.replies, "summaryCalls", run.summaryCalls,
                    "spawnedSubagents", 0, "businessEvaluation", "inspect raw tools and replies; completed only means SDK execution ended"));
        } catch (Exception failure) {
            run.trace.append("failure", Map.of("type", failure.getClass().getName(), "message", String.valueOf(failure.getMessage())), true);
            run.trace.finish("failed", Map.of("replies", run.replies, "error", String.valueOf(failure.getMessage()), "spawnedSubagents", 0));
            throw failure;
        } finally { System.out.println("RUN_DIRECTORY=" + run.trace.directory()); }
    }
}
