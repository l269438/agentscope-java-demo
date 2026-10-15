package dev.yiyan.agentscope.context;

import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/** 人工上下文+固定摘要响应；只验证真实中间件与文件，不证明LLM保留事实的质量。 */
public final class ContextCheck {
    public static final class SummaryFixture implements Model {
        public final List<List<Msg>> inputs = new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("fixture-summary: " + ContextDemo.FACT).build())).build());
        }
        @Override public String getModelName() { return "deterministic-compaction-fixture"; }
    }

    public static List<Msg> conversation() {
        return List.of(new UserMessage("模拟商品事实：" + ContextDemo.FACT),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("fixture-ack-1").build(),
                new UserMessage("按推荐和排除分组。"),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("fixture-ack-2").build(),
                new UserMessage("保留库存快照。"),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("fixture-ack-3").build());
    }

    public static Msg reportMessage() {
        return Msg.builder().role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.text(ContextDemo.REPORT).withIdAndName("report-06", "product_report"))).build();
    }

    public static String toolText(Msg message) {
        return message.getContentBlocks(ToolResultBlock.class).stream()
                .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).collect(Collectors.joining());
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of("target/local-checks/context-06", UUID.randomUUID().toString()).toAbsolutePath();
        Model summary = new SummaryFixture();
        try (var agent = ContextDemo.create(new OfflineModel(), summary, directory.resolve("workspace"), directory.resolve("states"))) {
            AgentState state = AgentState.builder().build(); state.contextMutable().addAll(conversation());
            RuntimeContext ctx = RuntimeContext.builder().userId("demo-user").sessionId("context-06").agentState(state).build();
            AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
            var input = new ReasoningInput(List.copyOf(state.getContext()), List.of(), null);
            agent.getCompactionHook().onReasoning(agent.getDelegate(), ctx, input,
                    next -> { forwarded.set(next); return Flux.empty(); }).collectList().block(Duration.ofSeconds(5));
            if (state.getContext().size() != 3 || forwarded.get().messages().size() != 3) throw new IllegalStateException("压缩结构不匹配");
            System.out.println("scriptedSummary=true; externalModelRequests=0");
            System.out.println("context-before=6; context-after=3; preserved-tail=2");
            var toolMessage = reportMessage(); state.contextMutable().add(toolMessage);
            var eviction = new ToolResultEvictionMiddleware(agent.getWorkspaceManager().getFilesystem(), ContextDemo.eviction());
            eviction.onReasoning(agent.getDelegate(), ctx, new ReasoningInput(List.of(toolMessage), List.of(), null),
                    next -> { forwarded.set(next); return Flux.empty(); }).collectList().block(Duration.ofSeconds(5));
            String placeholder = toolText(forwarded.get().messages().get(0));
            Path resultFile;
            try (var paths = Files.walk(directory.resolve("workspace"))) {
                resultFile = paths.filter(Files::isRegularFile).filter(p -> p.toString().contains("/large_tool_results/")).findFirst().orElseThrow();
            }
            if (!Files.readString(resultFile).equals(ContextDemo.REPORT)) throw new IllegalStateException("全文未保存");
            if (!placeholder.contains("read_file")) throw new IllegalStateException("缺少引用");
            System.out.println("tool-result-chars=" + ContextDemo.REPORT.length() + "; full-file-match=true");
            System.out.println("preview-has-read-file=true; context-middleware=PASS");
        }
    }
}
