package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.subagent.PlanApprovalGate;
import dev.yiyan.agentscope.subagent.SubagentCheck;
import dev.yiyan.agentscope.subagent.SubagentDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.PlanModeMiddleware;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** 不调用agent_spawn/agent_send；配置与主Agent计划权限检查不会启动子Agent。 */
class SubagentDemoTest {
    @TempDir Path root;
    private RuntimeContext ctx() { return SubagentDemo.context("alice", "subagents-08"); }
    private HarnessAgent create(io.agentscope.core.model.Model model) {
        return SubagentDemo.create(model, root.resolve("workspace"), "subagents-08");
    }
    private Msg pause(HarnessAgent agent) {
        agent.enterPlanMode(ctx()); return agent.call("fixture", ctx()).block(Duration.ofSeconds(10));
    }
    private Msg asking(String name) {
        return Msg.builder().role(MsgRole.ASSISTANT).generateReason(GenerateReason.PERMISSION_ASKING)
                .content(List.of(SubagentCheck.call(name, Map.of()).withState(ToolCallState.ASKING))).build();
    }

    @Test void declarationsLimitInheritedToolsAndKeepDistinctInstructions() {
        var roles = SubagentDemo.declarations();
        assertEquals(List.of("product-finder", "stock-reviewer"), roles.stream().map(d -> d.getName()).toList());
        for (var role : roles) {
            assertEquals(List.of("product_report"), role.getTools()); assertEquals(WorkspaceMode.ISOLATED, role.getWorkspaceMode());
            assertEquals(6, role.getSteps()); assertTrue(role.isInheritParentPermissions()); assertFalse(role.isPersistSession());
            assertFalse(role.isRemote()); assertTrue(role.getInlineAgentsBody().contains("不"));
        }
        assertTrue(roles.get(0).getInlineAgentsBody().contains("筛选")); assertTrue(roles.get(1).getInlineAgentsBody().contains("复核"));
    }
    @Test void declarationSourceModesAreMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class, () -> SubagentDeclaration.builder().name("role").description("fixture")
                .workspace(root).inlineAgentsBody("fixture").build());
        assertThrows(IllegalArgumentException.class, () -> SubagentDeclaration.builder().name("role").description("fixture")
                .url("http://127.0.0.1:1").inlineAgentsBody("fixture").build());
    }
    @Test void trustedContextForcesSynchronousModeAndRejectsUnsafeIdentifiers() {
        assertEquals(Boolean.TRUE, ctx().get(AgentSpawnTool.CTX_FORCE_SYNC));
        assertEquals(Integer.valueOf(30), (Integer) ctx().get(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> SubagentDemo.context("alice", "../another"));
        assertThrows(IllegalArgumentException.class, () -> SubagentDemo.context("", "s"));
    }
    @Test void parentAssemblyHasOnlyCaseToolsAndExplicitNetworkDeny() {
        try (var agent = create(new OfflineModel())) {
            assertEquals(SubagentDemo.TOOLS, agent.getToolkit().getToolNames());
            var rules = agent.getDelegate().getAgentState("alice", "subagents-08").getPermissionContext();
            assertTrue(rules.getDenyRules().containsKey("web_fetch")); assertTrue(rules.getDenyRules().containsKey("web_search"));
            assertFalse(agent.getToolkit().getToolNames().contains("write_file"));
        }
    }
    @Test void actualRootAgentWritesPlanAsksThenApprovedOriginalLeavesPlanMode() {
        var model = new SubagentCheck.PlanFixture();
        try (var agent = create(model)) {
            Msg result = pause(agent); assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason(),
                    model.inputs.stream().flatMap(List::stream).flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .map(dev.yiyan.agentscope.skill.SkillCheck::text).toList().toString());
            assertTrue(agent.isPlanModeActive("alice", "subagents-08"));
            assertEquals(SubagentCheck.PLAN, agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx(), "plans/subagents-08/PLAN.md"));
            var gate = new PlanApprovalGate(ctx(), Clock.systemUTC()); var preview = gate.capture(result);
            var original = preview.original(); Msg confirm = gate.message(ctx(), preview.ticket(), true);
            @SuppressWarnings("unchecked") var decisions = (List<ConfirmResult>) confirm.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            assertSame(original, decisions.get(0).getToolCall());
            assertEquals("fixture-plan-finished", agent.call(List.of(confirm), ctx()).block(Duration.ofSeconds(10)).getTextContent());
            assertFalse(agent.isPlanModeActive("alice", "subagents-08"));
            assertEquals(3, model.inputs.size());
            assertFalse(model.inputs.stream().flatMap(List::stream).flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                    .anyMatch(t -> t.getName().equals("agent_spawn") || t.getName().equals("agent_send")));
        }
    }
    @Test void rejectingOriginalPlanExitPreservesActivePlanAndFile() {
        try (var agent = create(new SubagentCheck.PlanFixture())) {
            var gate = new PlanApprovalGate(ctx(), Clock.systemUTC()); var preview = gate.capture(pause(agent));
            agent.call(List.of(gate.message(ctx(), preview.ticket(), false)), ctx()).block(Duration.ofSeconds(10));
            assertTrue(agent.isPlanModeActive("alice", "subagents-08"));
            assertEquals(SubagentCheck.PLAN, agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx(), "plans/subagents-08/PLAN.md"));
        }
    }
    @Test void planFlagIsScopedToExactUserAndSession() {
        try (var agent = create(new OfflineModel())) {
            agent.enterPlanMode("alice", "subagents-08");
            assertTrue(agent.isPlanModeActive("alice", "subagents-08"));
            assertFalse(agent.isPlanModeActive("bob", "subagents-08")); assertFalse(agent.isPlanModeActive("alice", "another"));
            agent.exitPlanMode("alice", "subagents-08"); assertFalse(agent.isPlanModeActive("alice", "subagents-08"));
        }
    }
    @Test void enterRecordsPathWriteOverwritesWholePlanAndExitKeepsReference() throws Exception {
        try (var agent = create(new OfflineModel())) {
            var state = AgentState.builder().build(); var manager = new PlanModeManager(agent.getWorkspaceManager(), "plans/subagents-08");
            assertEquals("plans/subagents-08/PLAN.md", manager.enter(state));
            try (var files = Files.walk(root)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().equals("PLAN.md"))); }
            manager.writePlan(ctx(), state, "first-plan"); manager.writePlan(ctx(), state, "replacement-plan");
            manager.exit(state); assertFalse(manager.isPlanActive(state));
            assertEquals("replacement-plan", agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx(), manager.planFilePath(state)));
            assertEquals("plans/subagents-08/PLAN.md", state.getPlanModeContext().getCurrentPlanFile());
        }
    }
    @Test void planGuardPartitionsCallsAndRecordsDeniedMutationWithoutForwardingIt() {
        try (var agent = create(new OfflineModel())) {
            var state = AgentState.builder().build(); var manager = new PlanModeManager(agent.getWorkspaceManager(), "plans"); manager.enter(state);
            var context = RuntimeContext.builder().userId("alice").sessionId("s").agentState(state).build();
            var next = new AtomicReference<ActingInput>();
            var middleware = new PlanModeMiddleware(manager, name -> "product_report".equals(name));
            var events = middleware.onActing(agent.getDelegate(), context,
                    new ActingInput(List.of(SubagentCheck.call("business_write", Map.of()), SubagentCheck.call("product_report", Map.of()))),
                    input -> { next.set(input); return Flux.empty(); }).collectList().block(Duration.ofSeconds(5));
            assertEquals(List.of("product_report"), next.get().toolCalls().stream().map(t -> t.getName()).toList());
            assertTrue(events.stream().anyMatch(e -> e instanceof ToolResultEndEvent end && end.getState() == ToolResultState.DENIED));
            var denied = state.getContext().stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).findFirst().orElseThrow();
            assertEquals("business_write", denied.getName()); assertEquals(ToolResultState.DENIED, denied.getState());
        }
    }
    @Test void planCoordinationExceptionsAreForwardedToRecorderWithoutLaunchingAnything() {
        try (var agent = create(new OfflineModel())) {
            var state = AgentState.builder().build(); var manager = new PlanModeManager(agent.getWorkspaceManager(), "plans"); manager.enter(state);
            var context = RuntimeContext.builder().agentState(state).build(); var next = new AtomicReference<ActingInput>();
            new PlanModeMiddleware(manager, name -> false).onActing(agent.getDelegate(), context,
                    new ActingInput(List.of(SubagentCheck.call("plan_write", Map.of()), SubagentCheck.call("agent_spawn", Map.of()))),
                    input -> { next.set(input); return Flux.empty(); }).collectList().block();
            assertEquals(List.of("plan_write", "agent_spawn"), next.get().toolCalls().stream().map(t -> t.getName()).toList());
            assertTrue(state.getContext().isEmpty()); // next仅收集调用；不会执行任何工具。
        }
    }
    @Test void confirmationIsBoundToIdentityOriginalTicketAndSingleUse() {
        var gate = new PlanApprovalGate(ctx(), Clock.systemUTC()); var preview = gate.capture(asking("plan_exit"));
        assertThrows(SecurityException.class, () -> gate.message(SubagentDemo.context("bob", "subagents-08"), preview.ticket(), true));
        assertThrows(IllegalStateException.class, () -> gate.message(ctx(), "different-ticket", true));
        assertThrows(IllegalStateException.class, () -> gate.capture(asking("plan_exit")));
        gate.message(ctx(), preview.ticket(), true);
        assertThrows(IllegalStateException.class, () -> gate.message(ctx(), preview.ticket(), true));
    }
    @Test void expiredTicketAndNonPlanOperationAreRejected() {
        var base = Instant.parse("2026-10-02T00:00:00Z");
        var changing = new AtomicReference<>(base);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return changing.get(); }
        };
        var gate = new PlanApprovalGate(ctx(), clock);
        assertThrows(IllegalArgumentException.class, () -> gate.capture(asking("save_shortlist")));
        var preview = gate.capture(asking("plan_exit")); changing.set(base.plus(Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> gate.message(ctx(), preview.ticket(), true));
        assertThrows(IllegalStateException.class, () -> gate.message(ctx(), preview.ticket(), true));
    }
}
