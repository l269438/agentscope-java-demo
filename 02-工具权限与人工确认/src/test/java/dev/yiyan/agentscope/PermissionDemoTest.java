package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.yiyan.agentscope.permission.ConfirmationGate;
import dev.yiyan.agentscope.permission.SelectionTools;
import dev.yiyan.agentscope.permission.PermissionCheck;
import dev.yiyan.agentscope.permission.PermissionDemo;
import dev.yiyan.agentscope.permission.ToolPolicy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.Toolkit;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionDemoTest {
    private final SelectionTools tools = new SelectionTools();
    private final RuntimeContext alice = context("alice", "selection-demo");
    private static RuntimeContext context(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }
    private static Map<String, Object> selection() { return PermissionCheck.SELECTION; }
    private ReActAgent agent(String tool, Map<String, Object> input, boolean maintenance) {
        return PermissionDemo.create(new PermissionCheck.ScriptedModel(tool, input), tools, maintenance);
    }
    private static List<ToolResultBlock> results(ReActAgent agent, RuntimeContext ctx) {
        return agent.getAgentState(ctx.getUserId(), ctx.getSessionId()).getContext().stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream()).toList();
    }

    @Test void schemasExposeBusinessInputsAndInjectBackendIdentity() {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tools);
        assertEquals(Set.of("product_info", "save_shortlist"), toolkit.getToolNames());
        for (var schema : toolkit.getToolSchemas()) {
            var properties = (Map<?, ?>) schema.getParameters().get("properties");
            assertFalse(properties.containsKey("context"));
            assertFalse(properties.containsKey("userId"));
        }
        assertTrue(toolkit.getTool("product_info").isReadOnly());
        assertFalse(toolkit.getTool("save_shortlist").isReadOnly());
        assertEquals(149, tools.productInfo("KB100", alice).get("priceYuan"));
        assertEquals(12, tools.productInfo("KB100", alice).get("stock"));
        assertThrows(SecurityException.class, () -> tools.productInfo("KB100", context("unknown", "selection")));
        assertThrows(SecurityException.class, () -> tools.saveShortlist("KB100", "办公备选", context("unknown", "selection")));
        assertThrows(SecurityException.class, () -> tools.productInfo("KB100", null));
        assertThrows(SecurityException.class, () -> tools.productInfo("KB100", RuntimeContext.empty()));
        assertThrows(IllegalArgumentException.class, () -> tools.productInfo(" ", alice));
        assertThrows(IllegalArgumentException.class, () -> tools.productInfo("UNKNOWN", alice));
    }

    @Test void validatesStockAndNoteAndPreventsDuplicateUserProduct() {
        for (String invalid : List.of("", " ", "x".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> tools.saveShortlist("KB100", invalid, alice));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.saveShortlist("KB100", null, alice));
        assertThrows(IllegalArgumentException.class, () -> tools.saveShortlist("KB200", "办公备选", alice));
        var saved = tools.saveShortlist("KB100", "办公备选", alice);
        assertEquals("模拟选购清单已保存", saved.get("status"));
        assertEquals("alice", saved.get("userId"));
        assertEquals(false, saved.get("orderCreated"));
        assertThrows(IllegalStateException.class, () -> tools.saveShortlist("KB100", "再次保存", alice));
        assertEquals(1, tools.selectionCount());
        tools.saveShortlist("KB100", "另一用户的备选", context("bob", "other"));
        assertEquals(2, tools.selectionCount());
        assertEquals(12, tools.productInfo("KB100", alice).get("stock"));
    }

    @Test void allowExecutesQueryAndMaintainsAcl() {
        try (var agent = agent("product_info", Map.of("sku", "KB100"), false)) {
            var reply = agent.call("fixture-query", alice).block(Duration.ofSeconds(5));
            assertNotEquals(GenerateReason.PERMISSION_ASKING, reply.getGenerateReason());
            assertTrue(results(agent, alice).stream().flatMap(t -> t.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).anyMatch(t -> t.getText().contains("静音无线键盘")), io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(results(agent, alice)));
            assertEquals(0, tools.selectionCount());
        }
        var unknown = context("unknown", "selection");
        try (var agent = agent("product_info", Map.of("sku", "KB100"), false)) {
            agent.call("fixture-unauthorized", unknown).block(Duration.ofSeconds(5));
            assertTrue(results(agent, unknown).stream().flatMap(t -> t.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).anyMatch(t -> t.getText().contains("无权")));
        }
    }

    @Test void askPausesBeforeWriteAndApprovesExactStoredOperation() {
        try (var agent = agent("save_shortlist", selection(), false)) {
            var pause = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            assertEquals(GenerateReason.PERMISSION_ASKING, pause.getGenerateReason());
            assertEquals(0, tools.selectionCount());
            var gate = new ConfirmationGate(alice, PermissionCheck.CLOCK);
            var preview = gate.capture(pause);
            var message = gate.confirmationMessage(alice, preview.ticket(), true);
            var decisions = (List<?>) message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            var decision = (ConfirmResult) decisions.get(0);
            assertSame(preview.operations().get(0), decision.getToolCall());
            assertEquals(selection(), decision.getToolCall().getInput());
            assertNull(decision.getRules());
            agent.call(List.of(message), alice).block(Duration.ofSeconds(5));
            assertEquals(1, tools.selectionCount(), io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(results(agent, alice)));
            assertEquals("fixture-call-02", results(agent, alice).get(0).getId());
            assertThrows(IllegalStateException.class, () -> gate.confirmationMessage(alice, preview.ticket(), true));
        }
    }

    @Test void rejectingLeavesNoSelectionAndRecordsDeniedResult() {
        try (var agent = agent("save_shortlist", selection(), false)) {
            var pause = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            var gate = new ConfirmationGate(alice, PermissionCheck.CLOCK);
            var preview = gate.capture(pause);
            agent.call(List.of(gate.confirmationMessage(alice, preview.ticket(), false)), alice).block(Duration.ofSeconds(5));
            assertEquals(0, tools.selectionCount());
            assertEquals(ToolResultState.DENIED, results(agent, alice).get(0).getState());
        }
    }

    @Test void maintenanceDenyWinsOverAskAndNeverExecutes() {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tools);
        var engine = new PermissionEngine(ToolPolicy.create(true));
        assertEquals(PermissionBehavior.DENY, engine.checkPermission((io.agentscope.core.tool.ToolBase) toolkit.getTool("save_shortlist"), selection()).block().getBehavior());
        try (var agent = agent("save_shortlist", selection(), true)) {
            var reply = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            assertNotEquals(GenerateReason.PERMISSION_ASKING, reply.getGenerateReason());
            assertEquals(0, tools.selectionCount());
            assertEquals(ToolResultState.DENIED, results(agent, alice).get(0).getState());
        }
    }

    @Test void wrongIdentitySessionAndTicketCannotConsumePending() {
        try (var agent = agent("save_shortlist", selection(), false)) {
            var pause = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            var gate = new ConfirmationGate(alice, PermissionCheck.CLOCK);
            var preview = gate.capture(pause);
            assertThrows(SecurityException.class, () -> gate.confirmationMessage(context("bob", "selection-demo"), preview.ticket(), true));
            assertThrows(SecurityException.class, () -> gate.confirmationMessage(context("alice", "other"), preview.ticket(), true));
            assertThrows(IllegalStateException.class, () -> gate.confirmationMessage(alice, "wrong-ticket", true));
            assertNotNull(gate.confirmationMessage(alice, preview.ticket(), false));
            assertEquals(0, tools.selectionCount());
        }
    }

    @Test void expiryAndUnexpectedCaptureFailClosed() {
        final var now = new java.util.concurrent.atomic.AtomicReference<>(PermissionCheck.CLOCK.instant());
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return SelectionTools.ZONE; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        var gate = new ConfirmationGate(alice, clock);
        assertThrows(IllegalArgumentException.class, () -> gate.capture(Msg.builder().textContent("ordinary").build()));
        var block = ToolUseBlock.builder().id("pending").name("save_shortlist").input(selection()).state(ToolCallState.ASKING).build();
        var pause = Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).content(List.of(block)).generateReason(GenerateReason.PERMISSION_ASKING).build();
        var preview = gate.capture(pause);
        assertThrows(IllegalStateException.class, () -> gate.capture(pause));
        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> gate.confirmationMessage(alice, preview.ticket(), true));
        assertThrows(IllegalStateException.class, () -> gate.confirmationMessage(alice, preview.ticket(), false));
    }

    @Test void approvalStillChecksStockAtExecution() {
        try (var agent = agent("save_shortlist", Map.of("sku", "KB200", "note", "办公备选"), false)) {
            var pause = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            var gate = new ConfirmationGate(alice, PermissionCheck.CLOCK);
            var preview = gate.capture(pause);
            agent.call(List.of(gate.confirmationMessage(alice, preview.ticket(), true)), alice).block(Duration.ofSeconds(5));
            assertEquals(0, tools.selectionCount());
            assertTrue(results(agent, alice).stream().flatMap(t -> t.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).anyMatch(t -> t.getText().contains("暂无库存")), io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(results(agent, alice)));
        }
    }

    @Test void approvalStillRejectsInvalidNote() {
        try (var agent = agent("save_shortlist", Map.of("sku", "KB100", "note", ""), false)) {
            var pause = agent.call("fixture-save", alice).block(Duration.ofSeconds(5));
            var gate = new ConfirmationGate(alice, PermissionCheck.CLOCK);
            var preview = gate.capture(pause);
            agent.call(List.of(gate.confirmationMessage(alice, preview.ticket(), true)), alice).block(Duration.ofSeconds(5));
            assertEquals(0, tools.selectionCount());
            assertTrue(results(agent, alice).stream().flatMap(t -> t.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).anyMatch(t -> t.getText().contains("选购备注")));
        }
    }
}
