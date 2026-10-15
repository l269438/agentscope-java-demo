package dev.yiyan.agentscope.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 仅执行主Agent的固定计划工具请求；从不调用agent_spawn/agent_send，不创建子Agent。 */
public final class SubagentCheck {
    public static final String PLAN = "# 模拟商品选购计划\n1. product-finder筛选200元以内的静音键盘。\n2. stock-reviewer核查候选商品库存快照。\n3. 主Agent汇总现货建议与排除理由，不下单。\n";
    private SubagentCheck() {}
    public static ToolUseBlock call(String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id("fixture-" + name).name(name).input(input)
                .content(JsonUtils.getJsonCodec().toJson(input)).build();
    }

    public static final class PlanFixture implements Model {
        private final AtomicInteger step = new AtomicInteger();
        public final List<List<Msg>> inputs = new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            List<ContentBlock> content = switch (step.getAndIncrement()) {
                case 0 -> List.of(call("plan_write", Map.of("content", PLAN)));
                case 1 -> List.of(call("plan_exit", Map.of("summary", "筛选商品并核查模拟库存")));
                default -> List.of(TextBlock.builder().text("fixture-plan-finished").build());
            };
            return Flux.just(ChatResponse.builder().content(content).build());
        }
        @Override public String getModelName() { return "deterministic-plan-fixture-no-subagents"; }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of("target/local-checks"); Files.createDirectories(directory);
        Path root = Files.createTempDirectory(directory, "subagents-08-");
        RuntimeContext ctx = SubagentDemo.context("demo-user", "subagents-08");
        System.out.println("scriptedModel=true; externalModelRequests=0; spawnedSubagents=0");
        for (boolean approve : List.of(true, false)) {
            try (var agent = SubagentDemo.create(new PlanFixture(), root.resolve(Boolean.toString(approve)), ctx.getSessionId())) {
                agent.enterPlanMode(ctx);
                Msg pause = agent.call("fixture", ctx).block(Duration.ofSeconds(10));
                var gate = new PlanApprovalGate(ctx, Clock.systemUTC()); var preview = gate.capture(pause);
                String path = agent.getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId())
                        .getPlanModeContext().getCurrentPlanFile();
                String saved = agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, path);
                if (!PLAN.equals(saved)) throw new IllegalStateException("计划原文不匹配");
                agent.call(List.of(gate.message(ctx, preview.ticket(), approve)), ctx).block(Duration.ofSeconds(10));
                boolean active = agent.isPlanModeActive(ctx.getUserId(), ctx.getSessionId());
                if (active == approve) throw new IllegalStateException("批准/拒绝与计划状态不一致");
                System.out.println("plan=" + path + "; approved=" + approve + "; planActive=" + active + "; file-match=true");
            }
        }
        System.out.println("declaredRoles=" + SubagentDemo.declarations().stream().map(d -> d.getName()).toList());
        System.out.println("plan-confirmation=PASS; subagent-execution=NOT-RUN");
    }
}
