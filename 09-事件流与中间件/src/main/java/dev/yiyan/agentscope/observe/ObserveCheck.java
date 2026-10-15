package dev.yiyan.agentscope.observe;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** 固定两轮模型内容驱动SDK事件，仅验证框架，不证明LLM整理结果。 */
public final class ObserveCheck {
    private ObserveCheck() {}
    public static final class FixtureModel implements Model {
        public final AtomicInteger requests = new AtomicInteger();
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            List<ContentBlock> content = requests.getAndIncrement() == 0
                    ? List.of(TextBlock.builder().text("fixture-intermediate").build(),
                        ToolUseBlock.builder().id("fixture-product-report").name("product_report").input(Map.of()).content("{}").build())
                    : List.of(TextBlock.builder().text("fixture-final").build());
            return Flux.just(ChatResponse.builder().content(content).build());
        }
        @Override public String getModelName() { return "deterministic-observation-fixture"; }
    }
    public static void main(String[] args) {
        System.out.println("scriptedModel=true; externalModelRequests=0; spawnedSubagents=0");
        var model = new FixtureModel(); var tools = new ObserveDemo.ProductTools(false);
        var middleware = new ObserveMiddleware(); var view = new EventView();
        try (var agent = ObserveDemo.create(model, tools, middleware)) {
            agent.streamEvents("fixture", ObserveDemo.context("fixture-request-09", true))
                    .doOnNext(view::accept).blockLast(Duration.ofSeconds(10));
        }
        if (!view.ended() || view.result() == null || !"fixture-final".equals(view.text()) || tools.reads.get() != 1) {
            throw new IllegalStateException("事件消费或最终文本过滤不匹配");
        }
        view.lines().forEach(System.out::println);
        System.out.println("modelRequests=" + model.requests.get() + "; toolReads=" + tools.reads.get()
                + "; filteredText=" + view.text() + "; hasResult=" + (view.result() != null));
        middleware.entries().forEach(System.out::println);
        System.out.println("events-middleware=PASS");
    }
}
