package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.yiyan.agentscope.support.OfflineModel;
import dev.yiyan.agentscope.workspace.WorkspaceDemo;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDemoTest {
    @TempDir Path directory;

    private Path prepare() throws Exception {
        Path root = directory.resolve("workspace");
        Files.createDirectories(root.resolve("knowledge"));
        Files.writeString(root.resolve("AGENTS.md"), "# 商品选购助手\n只根据资料推荐商品。");
        Files.writeString(root.resolve("knowledge/KNOWLEDGE.md"), "商品资料：products.md");
        Files.writeString(root.resolve("knowledge/products.md"), "KB100静音无线键盘149元，库存快照12件。\n来源缺失时待确认。");
        return root;
    }

    @Test void exposesOnlyReadToolsAndNoShell() throws Exception {
        try (var agent = WorkspaceDemo.create(new OfflineModel(), prepare())) {
            assertEquals(WorkspaceDemo.READ_TOOLS, agent.getToolkit().getToolNames());
            for (String name : agent.getToolkit().getToolNames()) {
                assertTrue(agent.getToolkit().getTool(name).isReadOnly());
            }
            assertFalse(agent.getToolkit().getToolNames().contains("execute"));
            assertFalse(agent.getToolkit().getToolNames().contains("write_file"));
        }
    }

    @Test void loadsAgentsAndKnowledgeIndex() throws Exception {
        try (var agent = WorkspaceDemo.create(new OfflineModel(), prepare())) {
            var manager = agent.getWorkspaceManager();
            assertTrue(manager.readAgentsMd(RuntimeContext.empty()).contains("只根据资料"));
            assertTrue(manager.readKnowledgeMd(RuntimeContext.empty()).contains("products.md"));
            assertTrue(manager.listKnowledgeFiles(RuntimeContext.empty()).stream()
                    .anyMatch(path -> path.toString().endsWith("products.md")));
        }
    }

    @Test void readsContentThroughRegisteredSdkTool() throws Exception {
        try (var agent = WorkspaceDemo.create(new OfflineModel(), prepare())) {
            String output = readTool(agent.getToolkit(), "knowledge/products.md");
            assertTrue(output.contains("KB100"), output);
            assertTrue(output.contains("149"), output);
            assertTrue(output.contains("待确认"), output);
        }
    }

    @Test void reportsMissingFileWithoutInventingContent() throws Exception {
        try (var agent = WorkspaceDemo.create(new OfflineModel(), prepare())) {
            String output = readTool(agent.getToolkit(), "knowledge/missing.md");
            assertTrue(output.contains("Error"), output);
        }
    }

    @Test void rootedFilesystemRejectsOutsideAbsolutePath() throws Exception {
        Path root = prepare();
        Path outside = directory.resolve("private.md");
        Files.writeString(outside, "OUTSIDE-FIXTURE");
        var filesystem = new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED)
                .toFilesystem(root, null);
        var result = filesystem.read(RuntimeContext.empty(), outside.toAbsolutePath().toString(), 0, 0);
        assertFalse(result.isSuccess(), String.valueOf(result.fileData()));
        assertNotNull(result.error());
    }

    @Test void rootedFilesystemRejectsTraversal() throws Exception {
        Path root = prepare();
        var filesystem = new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED)
                .toFilesystem(root, null);
        assertThrows(IllegalArgumentException.class,
                () -> filesystem.read(RuntimeContext.empty(), "../private.md", 0, 0));
    }

    @Test void detectsMissingWorkspaceInstructions() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDemo.create(new OfflineModel(), directory.resolve("missing")));
    }

    @Test void offlineModelForbidsRequests() {
        assertThrows(IllegalStateException.class,
                () -> new OfflineModel().stream(java.util.List.of(), java.util.List.of(), null).blockLast());
    }

    private String readTool(io.agentscope.core.tool.Toolkit toolkit, String path) {
        Map<String, Object> input = Map.of("path", path);
        ToolUseBlock use = ToolUseBlock.builder().id("workspace-test-read").name("read_file")
                .input(input).content(JsonUtils.getJsonCodec().toJson(input)).build();
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).build()).block();
        assertNotNull(result);
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
