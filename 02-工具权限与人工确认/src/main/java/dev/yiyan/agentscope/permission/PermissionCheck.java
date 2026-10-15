package dev.yiyan.agentscope.permission;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 固定工具请求仅检查真实SDK的权限分支；所有最终文本都是fixture，不代表LLM能力。 */
public final class PermissionCheck {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T02:00:00Z"), SelectionTools.ZONE);
    public static final Map<String, Object> SELECTION = Map.of("sku", "KB100", "note", "办公备选");
    private PermissionCheck() {}

    public static final class ScriptedModel implements Model {
        private final List<ContentBlock> calls;
        private final AtomicInteger requests = new AtomicInteger();
        public ScriptedModel(String name, Map<String, Object> input) {
            calls = List.of(ToolUseBlock.builder().id("fixture-call-02").name(name).input(input)
                    .content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(input)).build());
        }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            var content = requests.getAndIncrement() == 0 ? calls
                    : List.<ContentBlock>of(TextBlock.builder().text("fixture-finished").build());
            return Flux.just(ChatResponse.builder().content(content).build());
        }
        @Override public String getModelName() { return "deterministic-permission-fixture"; }
    }

    public static void main(String[] args) {
        System.out.println("scriptedModel=true; externalModelRequests=0; mockProductData=true");
        check(false, true);
        check(false, false);
        check(true, false);
        System.out.println("permission-branches=PASS");
    }

    private static void check(boolean maintenance, boolean approved) {
        var context = RuntimeContext.builder().userId("alice").sessionId("permission-02").build();
        var tools = new SelectionTools();
        var model = new ScriptedModel("save_shortlist", SELECTION);
        try (var agent = PermissionDemo.create(model, tools, maintenance)) {
            Msg pause = agent.call("fixture-request", context).block(Duration.ofSeconds(5));
            if (tools.selectionCount() != 0) throw new IllegalStateException("确认前出现写入");
            if (maintenance) {
                if (pause.getGenerateReason() == GenerateReason.PERMISSION_ASKING) throw new IllegalStateException("DENY错误进入ASK");
                System.out.println("maintenance=DENY; selections=0");
                return;
            }
            if (pause.getGenerateReason() != GenerateReason.PERMISSION_ASKING) throw new IllegalStateException("未暂停");
            var gate = new ConfirmationGate(context, CLOCK);
            var preview = gate.capture(pause);
            System.out.println("paused=" + pause.getGenerateReason() + "; selections=0");
            agent.call(List.of(gate.confirmationMessage(context, preview.ticket(), approved)), context).block(Duration.ofSeconds(5));
            int expected = approved ? 1 : 0;
            if (tools.selectionCount() != expected) throw new IllegalStateException("决定与写入不一致");
            System.out.println("approved=" + approved + "; selections=" + tools.selectionCount());
        }
    }
}
