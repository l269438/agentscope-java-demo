package dev.yiyan.agentscope.sandbox;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import reactor.core.publisher.Flux;

/** 默认纯配置检查；Docker集成须显式运行SandboxDockerCheck，不在mvn test自动启动容器。 */
public final class SandboxCheck {
    private SandboxCheck() {}
    public static ToolUseBlock call(String name, Map<String,Object> input) {
        return ToolUseBlock.builder().id("fixture-" + name + "-" + java.util.UUID.randomUUID()).name(name).input(input)
                .content(JsonUtils.getJsonCodec().toJson(input)).build();
    }
    public static final class FixtureModel implements Model {
        public final List<List<Msg>> inputs = new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int step = inputs.size(); inputs.add(List.copyOf(messages));
            ContentBlock block = switch (step) {
                case 0 -> call("execute", Map.of("command", SandboxDemo.COMMAND, "timeout", 10));
                case 1, 3, 5 -> call("read_file", Map.of("path", "selection-preview.md"));
                default -> TextBlock.builder().text("fixture-sandbox-finished").build();
            };
            return Flux.just(ChatResponse.builder().content(List.of(block)).build());
        }
        @Override public String getModelName() { return "deterministic-sandbox-fixture"; }
    }
    public static void main(String[] args) {
        var fs = SandboxDemo.filesystem("prepared-python-image", Path.of("target/check-snapshots-10"), new WorkspaceSpec(), null);
        var ctx = fs.toSandboxContext(); var options = (DockerSandboxClientOptions) ctx.getClientOptions();
        SandboxGuard.validate(call("execute", Map.of("command", SandboxDemo.COMMAND, "timeout", 10)));
        var a = SandboxIsolationKey.resolve(IsolationScope.SESSION, SandboxDemo.context("alice", "session-a"), "agent");
        var b = SandboxIsolationKey.resolve(IsolationScope.SESSION, SandboxDemo.context("bob", "session-b"), "agent");
        if (a.equals(b) || !"none".equals(options.getNetwork()) || options.getMemorySizeBytes() != SandboxDemo.MEMORY) {
            throw new IllegalStateException("配置边界不匹配");
        }
        System.out.println("configuration=PASS; dockerContainersStarted=0; externalModelRequests=0; spawnedSubagents=0");
        System.out.println("scope=" + ctx.getIsolationScope() + "; network=" + options.getNetwork() + "; memoryBytes=" + options.getMemorySizeBytes());
        System.out.println("snapshot=" + ctx.getSnapshotSpec().getClass().getSimpleName() + "; tools=" + SandboxDemo.TOOLS.stream().sorted().toList());
    }
}
