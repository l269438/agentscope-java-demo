package dev.yiyan.agentscope.observe;

import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次应用请求/订阅对应一个view；2.0.3请求与结果replyId可能不同，用source/call跨阶段关联。 */
public final class EventView {
    public record ToolKey(String source, String toolCallId) {}
    public record ToolProgress(String name, ToolResultState state, String callReplyId, String resultReplyId) {}
    public record TextKey(String replyId, String blockId) {}
    private final List<String> lines = new ArrayList<>();
    private final Map<ToolKey, ToolProgress> tools = new LinkedHashMap<>();
    private final Map<TextKey, StringBuilder> text = new LinkedHashMap<>();
    private Msg result;
    private boolean ended;

    public synchronized void accept(AgentEvent event) {
        lines.add(event.getType().name());
        if (event instanceof ToolCallStartEvent call) {
            tools.put(new ToolKey(event.getSource(), call.getToolCallId()),
                    new ToolProgress(call.getToolCallName(), ToolResultState.RUNNING, call.getReplyId(), null));
        } else if (event instanceof ToolResultEndEvent end) {
            ToolKey key = new ToolKey(event.getSource(), end.getToolCallId());
            ToolProgress prior = tools.get(key);
            tools.put(key, new ToolProgress(end.getToolCallName(), end.getState(),
                    prior == null ? null : prior.callReplyId(), end.getReplyId()));
        } else if (event instanceof TextBlockDeltaEvent delta && event.getSource() == null) {
            text.computeIfAbsent(new TextKey(delta.getReplyId(), delta.getBlockId()), key -> new StringBuilder()).append(delta.getDelta());
        } else if (event instanceof AgentResultEvent done && event.getSource() == null) {
            result = done.getResult();
        } else if (event instanceof AgentEndEvent && event.getSource() == null) {
            ended = true;
        }
    }
    public synchronized List<String> lines() { return List.copyOf(lines); }
    public synchronized Map<ToolKey, ToolProgress> tools() { return Map.copyOf(tools); }
    public synchronized String text() { return text.values().stream().map(StringBuilder::toString).collect(java.util.stream.Collectors.joining()); }
    public synchronized Map<TextKey, String> textBlocks() {
        Map<TextKey, String> copy = new LinkedHashMap<>(); text.forEach((key, value) -> copy.put(key, value.toString()));
        return Map.copyOf(copy);
    }
    public synchronized Msg result() { return result; }
    public synchronized boolean ended() { return ended; }
}
