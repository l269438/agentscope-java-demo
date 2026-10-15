package dev.yiyan.agentscope.workspace;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceDemo {
    public static final Set<String> READ_TOOLS = Set.of("read_file", "list_files", "glob_files", "grep_files");
    public static final String DEFAULT_MESSAGE = "读取商品目录、库存快照和选购规则，推荐200元以内适合办公室的静音现货键盘，说明排除其他商品的理由并标注来源文件。";

    private WorkspaceDemo() {}

    public static HarnessAgent create(Model model, Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("AGENTS.md"))) {
            throw new IllegalArgumentException("工作区缺少 AGENTS.md：" + root);
        }
        HarnessAgent agent = HarnessAgent.builder()
                .name("product-material-assistant")
                .model(model)
                .workspace(root)
                .filesystem(new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED))
                .toolsConfig(readOnlyCatalog())
                .stateStore(new InMemoryAgentStateStore())
                .disableShellTool().disableSubagents().disableDynamicSkills()
                .disableMemoryHooks().disableMemoryTools()
                .disableCompaction().disableToolResultEviction().disableTranscript()
                .maxIters(8).maxRetries(1)
                .build();
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!READ_TOOLS.contains(name)) {
                agent.getToolkit().removeTool(name);
            }
        }
        return agent;
    }

    private static ToolsConfig readOnlyCatalog() {
        ToolsConfig config = new ToolsConfig();
        config.setAllow(List.copyOf(READ_TOOLS));
        return config;
    }

    /** 模型入口：仅用户明确运行时才提交模型请求。 */
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        DemoConfig config = DemoConfig.load(project, System.getenv());
        String input = args.length == 0 ? DEFAULT_MESSAGE : String.join(" ", args);
        RuntimeContext context = RuntimeContext.builder()
                .userId("demo-user").sessionId("workspace-03").build();
        try (HarnessAgent agent = create(DemoModels.create(config), project.resolve("fixtures/workspace-03"))) {
            Msg reply = agent.call(input, context)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (reply != null) {
                System.out.println(reply.getGenerateReason() + "\n" + reply.getTextContent());
            }
        }
    }
}
