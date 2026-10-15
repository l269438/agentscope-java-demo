package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.yiyan.agentscope.context.ContextCheck;
import dev.yiyan.agentscope.context.ContextDemo;
import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import dev.yiyan.agentscope.support.RecordingFixtureModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class ContextDemoTest {
    @TempDir Path root;
    private HarnessAgent create(io.agentscope.core.model.Model summary) throws Exception {
        return ContextDemo.create(new OfflineModel(), summary, root.resolve("workspace"), root.resolve("states"));
    }
    private AgentState state(List<Msg> messages) {
        var state = AgentState.builder().build(); state.contextMutable().addAll(messages); return state;
    }
    private RuntimeContext context(AgentState state) {
        return RuntimeContext.builder().userId("demo-user").sessionId("context-06").agentState(state).build();
    }
    private ReasoningInput compact(HarnessAgent agent, AgentState state, List<Msg> view) {
        var forwarded = new AtomicReference<ReasoningInput>();
        agent.getCompactionHook().onReasoning(agent.getDelegate(), context(state), new ReasoningInput(view, List.of(), null),
                next -> { forwarded.set(next); return Flux.empty(); }).collectList().block(Duration.ofSeconds(5));
        return forwarded.get();
    }
    private ReasoningInput evict(HarnessAgent agent, AgentState state, List<Msg> view) {
        var forwarded = new AtomicReference<ReasoningInput>();
        new ToolResultEvictionMiddleware(agent.getWorkspaceManager().getFilesystem(), ContextDemo.eviction())
                .onReasoning(agent.getDelegate(), context(state), new ReasoningInput(view, List.of(), null),
                        next -> { forwarded.set(next); return Flux.empty(); }).collectList().block(Duration.ofSeconds(5));
        return forwarded.get();
    }
    private List<Path> resultFiles() throws Exception {
        Path directory = root.resolve("workspace");
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.walk(directory)) { return paths.filter(Files::isRegularFile)
                .filter(p -> p.toString().contains("/large_tool_results/")).toList(); }
    }

    @Test void explicitConfigurationUsesMessageTailAndClosedToolCatalog() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            assertEquals(ContextDemo.TOOLS, agent.getToolkit().getToolNames());
            for (String name : ContextDemo.TOOLS) assertTrue(agent.getToolkit().getTool(name).isReadOnly());
            assertNotNull(agent.getCompactionHook());
            var config = ContextDemo.compaction(new OfflineModel());
            assertEquals(6, config.getTriggerMessages()); assertEquals(12_000, config.getTriggerTokens());
            assertEquals(2, config.getKeepMessages()); assertEquals(0, config.getKeepTokens());
            assertFalse(config.isFlushBeforeCompact()); assertTrue(config.isOffloadBeforeCompact());
            assertNull(config.getPruneConfig());
            assertTrue(ContextDemo.eviction().getExcludedToolNames().contains("read_file"));
        }
    }

    @Test void compactsPrefixPreservesRecentTailAndBacksUpOriginal() throws Exception {
        var model = new ContextCheck.SummaryFixture();
        try (var agent = create(model)) {
            var messages = ContextCheck.conversation(); var state = state(messages);
            var system = Msg.builder().role(MsgRole.SYSTEM).textContent("system-rules").build();
            var view = new ArrayList<Msg>(); view.add(system); view.addAll(messages);
            var forwarded = compact(agent, state, view);
            assertEquals(3, state.getContext().size()); assertEquals(4, forwarded.messages().size());
            assertSame(system, forwarded.messages().get(0));
            assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, state.getContext().get(0).getName());
            assertSame(messages.get(4), state.getContext().get(1)); assertSame(messages.get(5), state.getContext().get(2));
            assertEquals(1, model.inputs.size());
            assertTrue(model.inputs.get(0).get(0).getTextContent().contains("按推荐和排除分组"));
            assertFalse(model.inputs.get(0).get(0).getTextContent().contains("fixture-ack-3"));
            var filesystem = agent.getWorkspaceManager().getFilesystem();
            Path transcript;
            try (var paths = Files.walk(root.resolve("workspace"))) {
                transcript = paths.filter(p -> p.toString().endsWith(".log.jsonl")).findFirst().orElseThrow();
            }
            String original = Files.readString(transcript);
            assertTrue(original.contains(ContextDemo.FACT)); assertTrue(original.contains("fixture-ack-3"));
            assertTrue(state.getContext().get(0).getTextContent().contains(".jsonl"));
            assertNotNull(filesystem);
        }
    }

    @Test void doesNotSummarizeBelowThreshold() throws Exception {
        var model = new ContextCheck.SummaryFixture();
        try (var agent = create(model)) {
            var messages = ContextCheck.conversation().subList(0, 4); var state = state(messages);
            var forwarded = compact(agent, state, messages);
            assertEquals(4, forwarded.messages().size()); assertTrue(model.inputs.isEmpty());
            assertSame(messages.get(0), state.getContext().get(0));
        }
    }

    @Test void failingSummaryCanStillReplacePrefixWithErrorMarker() throws Exception {
        try (var agent = create(new OfflineModel())) {
            var state = state(ContextCheck.conversation()); compact(agent, state, List.copyOf(state.getContext()));
            assertEquals(3, state.getContext().size());
            assertTrue(state.getContext().get(0).getTextContent().contains("Summarization failed:"));
            Path transcript;
            try (var paths = Files.walk(root.resolve("workspace"))) {
                transcript = paths.filter(p -> p.toString().endsWith(".log.jsonl")).findFirst().orElseThrow();
            }
            assertTrue(Files.readString(transcript).contains(ContextDemo.FACT));
        }
    }

    @Test void summaryOnlyChangesContextNotOtherAgentFields() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            var state = state(ContextCheck.conversation()); state.getPlanModeContext().setPlanActive(true);
            state.setSummary("separate-summary-field");
            compact(agent, state, List.copyOf(state.getContext()));
            assertTrue(state.getPlanModeContext().isPlanActive()); assertEquals("separate-summary-field", state.getSummary());
        }
    }

    @Test void evictsFullTextAndRebuildsCanonicalAndModelView() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            Msg original = ContextCheck.reportMessage(); var state = state(List.of(original));
            var forwarded = evict(agent, state, List.of(original));
            String preview = ContextCheck.toolText(forwarded.messages().get(0));
            assertTrue(preview.contains("read_file")); assertFalse(preview.contains(ContextDemo.FACT));
            assertTrue(preview.contains(ContextDemo.REPORT.substring(0, 200)));
            assertTrue(preview.contains(ContextDemo.REPORT.substring(ContextDemo.REPORT.length() - 200)));
            assertEquals(preview, ContextCheck.toolText(state.getContext().get(0)));
            assertEquals(ContextDemo.REPORT, ContextCheck.toolText(original));
            var files = resultFiles(); assertEquals(1, files.size()); assertEquals(ContextDemo.REPORT, Files.readString(files.get(0)));
            assertTrue(Files.readString(files.get(0)).contains(ContextDemo.FACT));
            var block = forwarded.messages().get(0).getContentBlocks(ToolResultBlock.class).get(0);
            assertEquals("report-06", block.getId()); assertEquals("product_report", block.getName());
            assertEquals(true, block.getMetadata().get("agentscope.tool_result_evicted"));
        }
    }

    @Test void excludesReadFileAndLeavesThresholdBoundaryUntouched() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            Msg excluded = Msg.builder().role(MsgRole.TOOL).content(List.of(ToolResultBlock.text(ContextDemo.REPORT)
                    .withIdAndName("read-06", "read_file"))).build();
            var state = state(List.of(excluded)); evict(agent, state, List.of(excluded));
            assertEquals(ContextDemo.REPORT, ContextCheck.toolText(state.getContext().get(0))); assertTrue(resultFiles().isEmpty());
            Msg boundary = Msg.builder().role(MsgRole.TOOL).content(List.of(ToolResultBlock.text("x".repeat(4_000))
                    .withIdAndName("boundary", "product_report"))).build();
            state = state(List.of(boundary)); evict(agent, state, List.of(boundary));
            assertEquals(4_000, ContextCheck.toolText(state.getContext().get(0)).length()); assertTrue(resultFiles().isEmpty());
        }
    }

    @Test void markedResultsAreNotEvictedAgain() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            var state = state(List.of(ContextCheck.reportMessage()));
            evict(agent, state, List.copyOf(state.getContext()));
            var first = state.getContext().get(0);
            evict(agent, state, List.copyOf(state.getContext()));
            assertSame(first, state.getContext().get(0)); assertEquals(1, resultFiles().size());
        }
    }

    @Test void failedFileWritePreservesOriginalResult() throws Exception {
        try (var agent = create(new ContextCheck.SummaryFixture())) {
            var original = ContextCheck.reportMessage(); var state = state(List.of(original));
            var blocked = agent.getWorkspaceManager().getFilesystem().write(context(state), "large_tool_results", "fixture-path-blocker");
            assertTrue(blocked.isSuccess());
            var forwarded = evict(agent, state, List.of(original));
            assertSame(original, state.getContext().get(0)); assertSame(original, forwarded.messages().get(0));
            assertEquals(ContextDemo.REPORT, ContextCheck.toolText(forwarded.messages().get(0)));
        }
    }

    @Test void actualAgentCallPersistsCompactedState() throws Exception {
        var primary = new RecordingFixtureModel(); var summary = new ContextCheck.SummaryFixture();
        var ctx = SessionStateDemo.context("demo-user", "context-06");
        try (var agent = ContextDemo.create(primary, summary, root.resolve("workspace"), root.resolve("states"))) {
            agent.getDelegate().getAgentState("demo-user", "context-06").contextMutable().addAll(ContextCheck.conversation());
            agent.getDelegate().saveAgentState("demo-user", "context-06");
            agent.call("仅列KB100的价格和库存。", ctx).block(Duration.ofSeconds(5));
            var saved = new JsonFileAgentStateStore(root.resolve("states")).get("demo-user", "context-06", "agent_state", AgentState.class).orElseThrow();
            assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, saved.getContext().get(0).getName());
            assertTrue(saved.getContext().stream().anyMatch(m -> "仅列KB100的价格和库存。".equals(m.getTextContent())));
            assertTrue(saved.getContext().stream().anyMatch(m -> "fixture-reply".equals(m.getTextContent())));
            assertEquals(1, summary.inputs.size());
        }
    }
}
