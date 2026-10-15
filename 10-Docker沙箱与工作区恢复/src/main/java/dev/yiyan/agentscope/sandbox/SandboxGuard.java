package dev.yiyan.agentscope.sandbox;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 本案例只允许指定脚本及预览文件，避免把ALLOW execute变成任意Shell授权。 */
public final class SandboxGuard implements MiddlewareBase {
    @Override public int order() { return 100; }
    public static void validate(ToolUseBlock call) {
        var input = call.getInput();
        if ("execute".equals(call.getName())) {
            Object timeout = input.get("timeout");
            if (!input.keySet().stream().allMatch(Set.of("command", "timeout", "working_directory")::contains)
                    || !SandboxDemo.COMMAND.equals(input.get("command"))
                    || !(timeout instanceof Number n) || n.doubleValue() != 10
                    || (input.get("working_directory") != null && !"".equals(input.get("working_directory")))) {
                throw new SecurityException("只允许指定预览脚本，timeout=10且使用默认工作目录");
            }
        } else if ("read_file".equals(call.getName())) {
            if (!input.keySet().stream().allMatch(Set.of("path", "offset", "limit")::contains)
                    || !Set.of("selection-preview.md", "/workspace/selection-preview.md").contains(input.get("path"))) {
                throw new SecurityException("只允许读取选购预览");
            }
        } else throw new SecurityException("工具未授权");
    }
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> { input.toolCalls().forEach(SandboxGuard::validate); return next.apply(input); });
    }
}
