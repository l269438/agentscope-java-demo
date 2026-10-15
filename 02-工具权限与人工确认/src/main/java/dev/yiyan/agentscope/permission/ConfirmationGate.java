package dev.yiyan.agentscope.permission;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 应用层示例：一实例绑定一个已认证用户/会话，串行使用，确认记录只驻留进程内。 */
public final class ConfirmationGate {
    public record Preview(String ticket, Instant expiresAt, List<ToolUseBlock> operations) {}
    private final String userId;
    private final String sessionId;
    private final Clock clock;
    private Preview pending;

    public ConfirmationGate(RuntimeContext authenticatedContext, Clock clock) {
        userId = Objects.requireNonNull(authenticatedContext.getUserId());
        sessionId = Objects.requireNonNull(authenticatedContext.getSessionId());
        if (userId.isBlank() || sessionId.isBlank()) throw new IllegalArgumentException("身份与会话不能为空");
        this.clock = Objects.requireNonNull(clock);
    }

    /** result 须来自本会话的Agent返回值；HTTP客户端没有提供工具对象的接口。 */
    public synchronized Preview capture(Msg result) {
        if (pending != null) throw new IllegalStateException("先处理已有待确认操作");
        if (result == null || result.getGenerateReason() != GenerateReason.PERMISSION_ASKING) {
            throw new IllegalArgumentException("需要Agent的待确认结果");
        }
        List<ToolUseBlock> operations = result.getContentBlocks(ToolUseBlock.class).stream()
                .filter(t -> t.getState() == ToolCallState.ASKING).toList();
        var ids = new HashSet<String>();
        if (operations.isEmpty() || operations.stream().anyMatch(t ->
                !"save_shortlist".equals(t.getName()) || t.getId() == null || t.getId().isBlank()
                        || !ids.add(t.getId()) || t.getInput().values().stream().anyMatch(v -> !(v instanceof String)))) {
            throw new IllegalArgumentException("待确认操作不符合此入口约定");
        }
        pending = new Preview(UUID.randomUUID().toString(), clock.instant().plus(Duration.ofMinutes(5)), operations);
        return pending;
    }

    /** authenticatedContext 必须由后端生成；只接收原ticket与决定，不接收客户端工具参数或建议规则。 */
    public synchronized Msg confirmationMessage(RuntimeContext authenticatedContext, String ticket, boolean approved) {
        if (!userId.equals(authenticatedContext.getUserId()) || !sessionId.equals(authenticatedContext.getSessionId())) {
            throw new SecurityException("确认用户或会话不匹配");
        }
        if (pending == null || !pending.ticket().equals(ticket)) throw new IllegalStateException("确认记录不存在或已消费");
        if (!clock.instant().isBefore(pending.expiresAt())) {
            pending = null;
            throw new IllegalStateException("确认已过期，请重新发起请求");
        }
        List<ConfirmResult> decisions = pending.operations().stream()
                .map(t -> new ConfirmResult(approved, t)).toList();
        Msg message = Msg.builder().name("user").role(MsgRole.USER)
                .textContent(approved ? "批准所展示的模拟操作" : "拒绝所展示的模拟操作")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, decisions)).build();
        pending = null;
        return message;
    }
}
