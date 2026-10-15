package dev.yiyan.agentscope.support;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

/** 装配与工具检查专用；任何模型调用都会失败，不能冒充模型运行结果。 */
public final class OfflineModel implements Model {
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.error(new IllegalStateException("离线检查禁止模型请求"));
    }

    @Override
    public String getModelName() {
        return "offline-no-model-requests";
    }
}
