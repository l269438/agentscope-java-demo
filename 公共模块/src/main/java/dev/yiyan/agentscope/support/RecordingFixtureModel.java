package dev.yiyan.agentscope.support;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/** 固定响应并记录输入，供跨章节生命周期测试复用，不请求外部模型。 */
public final class RecordingFixtureModel implements Model {
    public final List<List<Msg>> inputs = new ArrayList<>();

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        inputs.add(List.copyOf(messages));
        return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("fixture-reply").build())).build());
    }

    @Override public String getModelName() { return "deterministic-framework-fixture"; }
}
