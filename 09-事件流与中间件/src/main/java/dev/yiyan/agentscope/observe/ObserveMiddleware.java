package dev.yiyan.agentscope.observe;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 白名单日志只含请求ID、阶段与数量，不记录原文、工具参数、密钥或思考块。 */
public final class ObserveMiddleware implements MiddlewareBase {
    public record Entry(String requestId, String phase, int count, String signal) {}
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    public List<Entry> entries() { return List.copyOf(entries); }
    @Override public int order() { return 10; }
    private void record(RuntimeContext ctx, String phase, int count, String signal) {
        entries.add(new Entry(ctx.get("requestId"), phase, count, signal));
    }
    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            record(ctx, "agent-start", input.msgs().size(), "subscribed");
            return next.apply(input).doFinally(signal -> record(ctx, "agent-end", 0, signal.name()));
        });
    }
    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            record(ctx, "model", 1, "start"); return next.apply(input);
        });
    }
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!Boolean.TRUE.equals(ctx.get("catalogReadable"))) {
                return Flux.error(new SecurityException("商品资料访问被应用授权检查拒绝"));
            }
            record(ctx, "acting", input.toolCalls().size(), "start");
            return next.apply(input);
        });
    }
}
