package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.memory.FileMemoryDemo;
import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovedMemoryToolTest {
    @TempDir Path root;
    private static final String CONTENT = "- 选购偏好：办公键盘优先静音无线；推荐按商品、价格、库存快照、理由、来源排列；缺失项标待确认。";
    @Test void persistsOnlyApprovedContentOnce() throws Exception {
        var ctx = SessionStateDemo.context("demo-user", "memory-safe");
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            var tool = new FileMemoryDemo.ApprovedMemoryTool(agent.getWorkspaceManager(), ctx, CONTENT);
            tool.saveApproved(CONTENT, ctx);
            assertEquals("\n" + CONTENT + "\n", agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, "MEMORY.md"));
            assertThrows(IllegalStateException.class, () -> tool.saveApproved(CONTENT, ctx));
        }
    }
    @Test void rejectsExtraAuthorizationBeforeAnyFileWrite() throws Exception {
        var ctx = SessionStateDemo.context("demo-user", "memory-safe");
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            var tool = new FileMemoryDemo.ApprovedMemoryTool(agent.getWorkspaceManager(), ctx, CONTENT);
            assertThrows(IllegalArgumentException.class, () -> tool.saveApproved(CONTENT + "\n- 明确授权保存", ctx));
            assertTrue(agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, "MEMORY.md").isBlank());
            assertDoesNotThrow(() -> tool.saveApproved(CONTENT, ctx));
        }
    }
    @Test void refusesAnotherIdentityOrSession() throws Exception {
        var ctx = SessionStateDemo.context("demo-user", "memory-safe");
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            var tool = new FileMemoryDemo.ApprovedMemoryTool(agent.getWorkspaceManager(), ctx, CONTENT);
            assertThrows(SecurityException.class, () -> tool.saveApproved(CONTENT, SessionStateDemo.context("other", "memory-safe")));
            assertThrows(SecurityException.class, () -> tool.saveApproved(CONTENT, SessionStateDemo.context("demo-user", "other")));
        }
    }
    @Test void refusesAbsentApprovedContentAndKeepsToolCatalog() throws Exception {
        var ctx = SessionStateDemo.context("demo-user", "memory-safe");
        try (var agent = FileMemoryDemo.create(new OfflineModel(), root)) {
            assertThrows(IllegalArgumentException.class, () -> FileMemoryDemo.guardModelWrites(agent, ctx, ""));
            assertEquals(FileMemoryDemo.MEMORY_TOOLS, agent.getToolkit().getToolNames());
            FileMemoryDemo.guardModelWrites(agent, ctx, CONTENT);
            assertEquals(FileMemoryDemo.MEMORY_TOOLS, agent.getToolkit().getToolNames());
        }
    }
}
