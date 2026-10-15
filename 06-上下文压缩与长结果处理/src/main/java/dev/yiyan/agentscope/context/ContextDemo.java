package dev.yiyan.agentscope.context;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.session.SessionStateDemo;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** 两条上下文路径使用自制长报告；业务操作只读，内部压缩日志/结果卸载会写工作区。 */
public final class ContextDemo {
    public static final String GUIDED_MESSAGE = "查询长篇模拟商品选购报告，找出200元以内的静音现货键盘。结果卸载后，在工具返回的文件路径用grep_files搜索字面词选购结论。"
            + "按命中行号用read_file读取：offset等于行号减1，limit=3。"
            + "只根据原文说明商品、价格、库存快照、推荐理由与来源；搜索无命中只表示这个查询未命中。";
    public static final String RULES = "只用报告中的选购结论判断商品、价格与库存；背景讨论不能作为在售事实。"
            + "大工具结果若只提供预览和文件引用，先沿原引用用grep_files搜索单个字面词选购结论；这个工具不支持正则，不传竖线组合词。"
            + "找到行号后用read_file读取附近3行，必须传offset和limit=3；offset为命中行号减1。不要省略limit读取全文。"
            + "预览缺少价格或库存不能证明全文没有；摘要可能遗漏细节，关键字段回到原文件核对。"
            + "不猜文件路径；来源用实际读取的文件和行号，缺项写待确认。库存是模拟快照，不下单。";
    public static final String FACT = "选购结论：预算200元，静音无线办公。KB100静音无线键盘149元，模拟库存12件，推荐；KB200轻薄无线键盘199元，模拟库存0件，排除；KB300机械键盘299元，超预算，排除。来源：模拟商品目录与库存快照。";
    public static final String REPORT = "# 模拟商品选购报告\n"
            + "背景讨论：本段讨论键盘布局、办公习惯和历史产品设计，不提供当前商品报价、库存或推荐依据。\n".repeat(160)
            + FACT + "\n"
            + "后续讨论：本段为产品设计历史资料，不新增商品，不调整预算，也不修改本次库存快照。\n".repeat(160)
            + "报告结束：本次推荐依据中间的选购结论。\n";
    public static final Set<String> TOOLS = Set.of("product_report", "read_file", "list_files", "glob_files", "grep_files");
    private ContextDemo() {}

    public static final class ReportTools {
        @Tool(name = "product_report", description = "获取长篇模拟商品选购报告全文", readOnly = true, concurrencySafe = true)
        public ToolResultBlock report() { return ToolResultBlock.text(REPORT); }
    }

    public static CompactionConfig compaction(Model summaryModel) {
        return CompactionConfig.builder().model(summaryModel)
                .triggerMessages(6).triggerTokens(12_000)
                .keepMessages(2).keepTokens(0)
                .flushBeforeCompact(false).offloadBeforeCompact(true)
                .prune(null).build();
    }

    public static ToolResultEvictionConfig eviction() {
        return ToolResultEvictionConfig.builder()
                .maxResultChars(4_000).previewChars(200)
                .evictionPath("large_tool_results").build();
    }

    public static HarnessAgent create(Model primaryModel, Model summaryModel, Path ownedWorkspace, Path stateDirectory) throws Exception {
        Path root = ownedWorkspace.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.exists(root.resolve("AGENTS.md"))) {
            Files.writeString(root.resolve("AGENTS.md"), "# 模拟商品选购\n先查询报告，按商品、价格、库存快照、理由与来源回答。需要细节时读取卸载文件。\n");
        }
        HarnessAgent agent = HarnessAgent.builder().name("shopping-context-assistant")
                .model(primaryModel).sysPrompt(RULES).workspace(root)
                .filesystem(new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED))
                .stateStore(new JsonFileAgentStateStore(stateDirectory))
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.EXPLORE).build())
                .compaction(compaction(summaryModel)).toolResultEviction(eviction())
                .disableMemoryHooks().disableMemoryTools().disableShellTool()
                .disableSubagents().disableDynamicSkills().disableTranscript()
                .maxIters(8).maxRetries(1).build();
        agent.getToolkit().registerTool(new ReportTools());
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!TOOLS.contains(name)) agent.getToolkit().removeTool(name);
        }
        return agent;
    }

    /** 显式运行才请求已有提供商；同user/session保存状态，辅助摘要模型此处沿用主模型配置。 */
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        var config = DemoConfig.load(project, System.getenv());
        var context = SessionStateDemo.context("demo-user", args.length == 0 ? "context-06" : args[0]);
        String message = args.length < 2 ? GUIDED_MESSAGE
                : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        Model model = DemoModels.create(config);
        try (var agent = create(model, model, project.resolve("target/context-06/demo-user"), project.resolve("target/context-state-06"))) {
            var result = agent.call(message, context).timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (result != null) System.out.println(result.getTextContent());
        }
    }
}
