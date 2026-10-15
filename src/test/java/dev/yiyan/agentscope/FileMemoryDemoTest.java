package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.yiyan.agentscope.memory.FileMemoryDemo;
import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryDemoTest {
    @TempDir Path root;
    private final io.agentscope.core.agent.RuntimeContext ctx = SessionStateDemo.context("demo-user", "selection-a");

    @Test void onlyThreeMemoryToolsAreExposed() throws Exception {
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            assertEquals(FileMemoryDemo.MEMORY_TOOLS, agent.getToolkit().getToolNames());
            assertFalse(agent.getToolkit().getTool("memory_save").isReadOnly());
            assertTrue(agent.getToolkit().getTool("memory_get").isReadOnly());
            assertTrue(agent.getToolkit().getTool("memory_search").isReadOnly());
        }
    }

    @Test void saveAppendsToBothLongTermAndDailyFiles() throws Exception {
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            assertTrue(FileMemoryDemo.invoke(agent, ctx, "memory_save", Map.of("content", FileMemoryDemo.FACT)).contains("Saved"));
            var manager = agent.getWorkspaceManager();
            assertTrue(manager.readMemoryMd(ctx).contains(FileMemoryDemo.FACT));
            String daily = manager.readManagedWorkspaceFileUtf8(ctx, "memory/" + LocalDate.now() + ".md");
            assertTrue(daily.contains(FileMemoryDemo.FACT));
            assertTrue(daily.contains("Memory Save"));
        }
    }

    @Test void blankContentIsRejectedWithoutSavingFacts() throws Exception {
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            String output = FileMemoryDemo.invoke(agent, ctx, "memory_save", Map.of("content", " "));
            assertTrue(output.contains("Error: content is required"));
            assertFalse(agent.getWorkspaceManager().readMemoryMd(ctx).contains(FileMemoryDemo.FACT));
        }
    }

    @Test void newSessionAndNewAgentReadSameOwnerWorkspace() throws Exception {
        try (var first = FileMemoryDemo.create(new OfflineModel(), root)) {
            FileMemoryDemo.invoke(first, ctx, "memory_save", Map.of("content", FileMemoryDemo.FACT));
        }
        try (var second = FileMemoryDemo.create(new OfflineModel(), root)) {
            var next = SessionStateDemo.context("demo-user", "selection-b");
            assertTrue(FileMemoryDemo.invoke(second, next, "memory_search", Map.of("query", "选购偏好")).contains("Source:"));
            assertTrue(FileMemoryDemo.invoke(second, next, "memory_get", Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 20)).contains(FileMemoryDemo.FACT));
            var middleware = new WorkspaceContextMiddleware(second.getWorkspaceManager(), "memory-demo", null, 8000, false, true);
            String prompt = middleware.onSystemPrompt(second, next, "模拟项目助手").block(Duration.ofSeconds(5));
            assertTrue(prompt.contains(FileMemoryDemo.FACT));
        }
    }

    @Test void separateOwnerWorkspacesDoNotShareFacts() throws Exception {
        try (var first = FileMemoryDemo.create(new OfflineModel(), root.resolve("owner-a"))) {
            FileMemoryDemo.invoke(first, ctx, "memory_save", Map.of("content", FileMemoryDemo.FACT));
        }
        try (var second = FileMemoryDemo.create(new OfflineModel(), root.resolve("owner-b"))) {
            assertFalse(second.getWorkspaceManager().readMemoryMd(SessionStateDemo.context("owner-b", "selection")).contains(FileMemoryDemo.FACT));
        }
    }

    @Test void memoryGetRejectsTraversal() throws Exception {
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            assertTrue(FileMemoryDemo.invoke(agent, ctx, "memory_get", Map.of("path", "../outside.md", "startLine", 1, "endLine", 20)).contains("path traversal not allowed"));
        }
    }

    @Test void explicitUpdatesAreAppendedUntilConsolidation() throws Exception {
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            FileMemoryDemo.invoke(agent, ctx, "memory_save", Map.of("content", "- 模拟项目：推荐只列商品与价格。"));
            FileMemoryDemo.invoke(agent, ctx, "memory_save", Map.of("content", FileMemoryDemo.FACT));
            assertTrue(agent.getWorkspaceManager().readMemoryMd(ctx).contains("只列商品与价格"));
            assertTrue(agent.getWorkspaceManager().readMemoryMd(ctx).contains("来源排列"));
        }
    }

    @Test void consolidationWritesFixtureOutputAndSkipsWithoutFreshLedger() throws Exception {
        SessionStateDemoTest.FixtureModel model = new SessionStateDemoTest.FixtureModel();
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            FileMemoryDemo.invoke(agent, ctx, "memory_save", Map.of("content", FileMemoryDemo.FACT));
            var consolidator = new MemoryConsolidator(agent.getWorkspaceManager(), model);
            consolidator.consolidate(ctx).block(Duration.ofSeconds(5));
            assertEquals("fixture-reply", agent.getWorkspaceManager().readMemoryMd(ctx));
            assertTrue(model.inputs.get(0).stream().anyMatch(msg -> msg.getTextContent().contains(FileMemoryDemo.FACT)));
            assertFalse(agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, "memory/.consolidation_state").isBlank());
            consolidator.consolidate(ctx).block(Duration.ofSeconds(5));
            assertEquals(1, model.inputs.size());
        }
    }

    @Test void automaticPolicyUsesIndependentFlushAndConsolidationGaps() {
        var config = FileMemoryDemo.automaticPolicy(new OfflineModel());
        assertEquals(MemoryConfig.FlushMode.THROTTLED, config.flushTrigger().mode());
        assertEquals(Duration.ofMinutes(10), config.flushTrigger().minGap());
        assertEquals(Duration.ofHours(2), config.consolidationMinGap());
        assertNotNull(config.model());
        assertThrows(IllegalArgumentException.class, () -> MemoryConfig.builder().consolidationPrompt("wrong").build());
    }
}
