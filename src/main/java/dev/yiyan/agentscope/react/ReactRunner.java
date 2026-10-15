package dev.yiyan.agentscope.react;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.RunTrace;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ReactRunner {
    public static final String PROMPT_REVISION = "stock-check-v2";
    public static final String PROMPT = "你是电商键盘选购助手，金额单位为人民币元。严格按以下步骤执行：\n"
            + "1. 调用find_products，按用户预算和必需特点筛选候选。查询为空时直接说明没有匹配商品。\n"
            + "2. 查询非空时，调用get_stock逐一查询每个候选sku的库存。sku必须来自find_products结果。"
            + "还有候选没有查库存时，继续调用get_stock，禁止直接结束或给出推荐。\n"
            + "3. 全部库存查完后，仅推荐inStock=true且stock大于0的商品。列出sku、名称、价格、特点和库存件数；"
            + "说明库存为0的候选不予推荐。商品特点与价格只能来自find_products，库存只能来自get_stock。\n"
            + "缺少预算或特点时先询问。工具失败时如实说明。所有数据均为模拟数据，查询不锁库、不下单。";
    public static final String DEFAULT_MESSAGE = "预算200元，想买适合办公室的静音键盘。"
            + "只推荐有现货的商品，并说明价格、特点和库存。";

    public record Reply(String text, String generateReason, boolean stageSummary) {}

    /** 装配生产模型入口，不提交模型请求。 */
    public ReActAgent createAgent(DemoConfig config, RunTrace trace) {
        ChatModelBase model;
        ExecutionConfig execution = ExecutionConfig.builder().maxAttempts(1)
                .timeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        if (config.provider().equals("ollama")) {
            model = OllamaChatModel.builder().baseUrl(config.ollamaBaseUrl()).modelName(config.modelName())
                    .stream(true).contextWindowSize(8192)
                    .defaultOptions(OllamaOptions.builder().numCtx(8192).numPredict(1024)
                            .temperature(0.0).executionConfig(execution).build()).build();
        } else {
            model = DashScopeChatModel.builder().apiKey(config.apiKey())
                    .modelName(config.modelName()).stream(true).enableThinking(false).enableSearch(false)
                    .defaultOptions(GenerateOptions.builder().maxTokens(1024).temperature(0.0)
                            .executionConfig(execution).build()).build();
        }
        return createAgent(config, trace, model);
    }

    /** 允许离线检查注入禁止请求的 Model，避免构建本地或云端模型客户端。 */
    public ReActAgent createAgent(DemoConfig config, RunTrace trace, Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProductQueryTools(trace));
        return ReActAgent.builder().name("ecommerce-assistant")
                .sysPrompt(PROMPT).model(model).toolkit(toolkit)
                .maxIters(config.maxIters()).maxRetries(1).build();
    }

    public Reply run(DemoConfig config, String message, RunTrace trace) {
        AtomicReference<Msg> finalMessage = new AtomicReference<>();
        try (ReActAgent agent = createAgent(config, trace)) {
            agent.streamEvents(new UserMessage(message)).doOnNext(event -> {
                String type = event.getType().name();
                // 不记录模型思维片段；保留应用可见文本、工具事件及 usage。
                if (type.startsWith("THINKING_")) return;
                if (event instanceof ModelCallStartEvent) trace.modelCallStarted();
                if (event instanceof AgentResultEvent result) {
                    finalMessage.set(result.getResult());
                    return;
                }
                trace.append(type, event, !(event instanceof TextBlockDeltaEvent) && !type.equals("TOOL_CALL_DELTA"));
            }).timeout(Duration.ofSeconds(config.timeoutSeconds())).blockLast();
        }
        Msg reply = finalMessage.get();
        if (reply == null) throw new IllegalStateException("未收到 AgentResultEvent");
        Reply result = new Reply(reply.getTextContent(),
                reply.getGenerateReason() == null ? "UNSPECIFIED" : reply.getGenerateReason().name(),
                reply.getGenerateReason() == GenerateReason.MAX_ITERATIONS);
        trace.append("final_reply", result, true);
        return result;
    }

    public static Map<String, Object> context() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProductQueryTools());
        return Map.of("promptRevision", PROMPT_REVISION, "systemPrompt", PROMPT, "dataset", ProductQueryTools.dataset(),
                "tools", toolkit.getToolSchemas());
    }
}
