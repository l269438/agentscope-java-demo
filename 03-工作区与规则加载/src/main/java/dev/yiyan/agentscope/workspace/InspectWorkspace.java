package dev.yiyan.agentscope.workspace;

import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

/** 本地 SDK 文件工具输出；不执行 Agent.call，不生成商品推荐答案。 */
public final class InspectWorkspace {
    public static void main(String[] args) {
        Path workspace = Path.of("03-工作区与规则加载/fixtures/workspace-03");
        try (HarnessAgent agent = WorkspaceDemo.create(new OfflineModel(), workspace)) {
            System.out.println("tools=" + new TreeSet<>(agent.getToolkit().getToolNames()));
            Map<String, Object> input = Map.of("path", "knowledge/products.md");
            ToolUseBlock use = ToolUseBlock.builder().id("workspace-read-01").name("read_file")
                    .input(input).content(JsonUtils.getJsonCodec().toJson(input)).build();
            var result = agent.getToolkit().callTool(ToolCallParam.builder().toolUseBlock(use).build()).block();
            if (result == null) throw new IllegalStateException("没有文件工具结果");
            result.getOutput().stream().filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).forEach(block -> System.out.println(block.getText()));
        }
    }
}
