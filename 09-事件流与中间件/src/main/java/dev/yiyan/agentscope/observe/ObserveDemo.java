package dev.yiyan.agentscope.observe;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.skill.SkillDemo;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 单次事件流消费，无HTTP服务器、无子Agent；默认固定响应，--run才请求配置的模型。 */
public final class ObserveDemo {
    private ObserveDemo() {}
    public static final class ProductTools {
        public final AtomicInteger reads = new AtomicInteger();
        private final boolean fail;
        public ProductTools(boolean fail) { this.fail = fail; }
        @Tool(name = "product_report", description = "读取模拟商品与库存快照", readOnly = true, concurrencySafe = true)
        public ToolResultBlock report(RuntimeContext ctx) {
            if (ctx == null || !Boolean.TRUE.equals(ctx.get("catalogReadable"))) throw new SecurityException("无权读取商品资料");
            reads.incrementAndGet();
            if (fail) throw new IllegalStateException("模拟资料暂不可用");
            return ToolResultBlock.text(SkillDemo.REPORT);
        }
    }

    /** 属性由应用的认证/授权路径决定，不能接受客户端自报的catalogReadable。 */
    public static RuntimeContext context(String requestId, boolean catalogReadable) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("请求ID不合法");
        return RuntimeContext.builder().userId("demo-user").sessionId("observe-09")
                .put("requestId", requestId).put("catalogReadable", catalogReadable).build();
    }

    public static ReActAgent create(Model model, ProductTools tools, ObserveMiddleware middleware) {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tools);
        return ReActAgent.builder().name("shopping-observer")
                .sysPrompt("查询模拟商品与库存，推荐200元以内的静音现货键盘，保留价格、库存快照、理由和来源。工具报错时说明资料不可用，不猜测，不下单。")
                .model(model).toolkit(toolkit).stateStore(new InMemoryAgentStateStore())
                .middleware(middleware).middleware(new FinalAnswerFilterMiddleware())
                .middleware(new OtelTracingMiddleware()).maxIters(4).maxRetries(1).build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { ObserveCheck.main(args); return; }
        if (args.length != 1 || !"--run".equals(args[0])) throw new IllegalArgumentException("参数：--run；默认固定响应检查");
        var config = DemoConfig.load(Path.of("").toAbsolutePath(), System.getenv());
        var trace = new ObserveMiddleware(); var view = new EventView();
        RuntimeContext ctx = context(UUID.randomUUID().toString(), true);
        try (var agent = create(DemoModels.create(config), new ProductTools(false), trace)) {
            agent.streamEvents("推荐200元以内的静音现货键盘，并说明排除理由。", ctx)
                    .doOnNext(view::accept).blockLast(Duration.ofSeconds(config.timeoutSeconds()));
            view.lines().forEach(System.out::println);
            if (view.result() != null) System.out.println(view.result().getTextContent());
        }
    }
}
