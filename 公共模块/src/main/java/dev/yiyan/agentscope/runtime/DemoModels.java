package dev.yiyan.agentscope.runtime;

import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Duration;

/** 后续专题共用既有提供商配置；只构建模型，不提交请求。 */
public final class DemoModels {
    private DemoModels() {}

    public static ChatModelBase create(DemoConfig config) {
        if (!config.modelConfigurationReady()) {
            throw new IllegalStateException("请先配置模型提供商；离线检查无需模型配置");
        }
        ExecutionConfig execution = ExecutionConfig.builder().maxAttempts(1)
                .timeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        if (config.provider().equals("ollama")) {
            return OllamaChatModel.builder().baseUrl(config.ollamaBaseUrl()).modelName(config.modelName())
                    .stream(true).contextWindowSize(8192)
                    .defaultOptions(OllamaOptions.builder().numCtx(8192).numPredict(1024)
                            .temperature(0.0).executionConfig(execution).build()).build();
        }
        return DashScopeChatModel.builder().apiKey(config.apiKey())
                .modelName(config.modelName()).stream(true).enableThinking(false).enableSearch(false)
                .defaultOptions(GenerateOptions.builder().maxTokens(1024).temperature(0.0)
                        .executionConfig(execution).build()).build();
    }
}
