package dev.yiyan.agentscope.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 进程内、串行确认入口；仅确认服务端捕获的原plan_exit，不接收客户端工具参数。 */
public final class PlanApprovalGate {
    public record Preview(String ticket, Instant expiresAt, ToolUseBlock original) {}
    private final String user;
    private final String session;
    private final Clock clock;
    private Preview pending;

    public PlanApprovalGate(RuntimeContext authenticatedContext, Clock clock) {
        user = Objects.requireNonNull(authenticatedContext.getUserId());
        session = Objects.requireNonNull(authenticatedContext.getSessionId());
        if (user.isBlank() || session.isBlank()) throw new IllegalArgumentException("身份与会话不能为空");
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized Preview capture(Msg result) {
        if (pending != null) throw new IllegalStateException("先处理已有计划确认");
        if (result == null || result.getGenerateReason() != GenerateReason.PERMISSION_ASKING) {
            throw new IllegalArgumentException("需要计划退出的ASK结果");
        }
        var calls = result.getContentBlocks(ToolUseBlock.class);
        if (calls.size() != 1 || !"plan_exit".equals(calls.get(0).getName())
                || calls.get(0).getState() != ToolCallState.ASKING) {
            throw new IllegalArgumentException("此入口只能确认一个原始plan_exit");
        }
        pending = new Preview(UUID.randomUUID().toString(), clock.instant().plus(Duration.ofMinutes(5)), calls.get(0));
        return pending;
    }

    public synchronized Msg message(RuntimeContext authenticatedContext, String ticket, boolean approved) {
        if (!user.equals(authenticatedContext.getUserId()) || !session.equals(authenticatedContext.getSessionId())) {
            throw new SecurityException("确认用户或会话不匹配");
        }
        if (pending == null || !pending.ticket().equals(ticket)) throw new IllegalStateException("确认不存在或已消费");
        if (!clock.instant().isBefore(pending.expiresAt())) { pending = null; throw new IllegalStateException("计划确认已过期"); }
        var decision = new ConfirmResult(approved, pending.original());
        Msg message = Msg.builder().name("user").role(MsgRole.USER)
                .textContent(approved ? "批准所展示的计划" : "拒绝所展示的计划")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(decision))).build();
        pending = null; return message;
    }
}
