package dev.yiyan.agentscope.memory;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.session.SessionStateDemo;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 用户专用工作区中的文件记忆；关闭自动辅助调用，保留显式记忆工具。 */
public final class FileMemoryDemo {
    public static final Set<String> MEMORY_TOOLS = Set.of("memory_save", "memory_search", "memory_get");
    public static final String FACT = "- 选购偏好：办公键盘优先静音无线；推荐按商品、价格、库存快照、理由、来源排列；缺失项标待确认。";
    public static final String RULES = "记忆提供可复用的选购偏好，价格和库存以本轮资料为准。"
            + "memory_search使用连续短关键词，例如选购偏好；未匹配时用memory_get读取MEMORY.md。"
            + "按偏好筛选本轮商品，缺项写待确认。历史库存不能当作实时可售。"
            + "只有用户指定的偏好可保存；本次授权决定和工具许可不属于长期事实，不写入记忆。不下单、不付款。";
    private FileMemoryDemo() {}

    public static HarnessAgent create(Model model, Path ownedWorkspace) throws Exception {
        Path root = ownedWorkspace.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path rules = root.resolve("AGENTS.md");
        if (!Files.exists(rules)) Files.writeString(rules, "# 电商选购助手\n仅保存明确且可复用的选购偏好；不要记录密钥、支付资料或个人隐私。\n");
        HarnessAgent agent = HarnessAgent.builder()
                .name("file-memory-assistant")
                .model(model)
                .sysPrompt(RULES)
                .workspace(root)
                .filesystem(new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED))
                .stateStore(new InMemoryAgentStateStore())
                .disableMemoryHooks()
                .disableShellTool().disableSubagents().disableDynamicSkills()
                .disableCompaction().disableToolResultEviction().disableTranscript()
                .maxIters(8).maxRetries(1)
                .build();
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!MEMORY_TOOLS.contains(name)) agent.getToolkit().removeTool(name);
        }
        return agent;
    }

    /** 模型提出内容，后端按本次已批准的约定校验；不从模型文字或MEMORY推导授权。 */
    public static void guardModelWrites(HarnessAgent agent, RuntimeContext authenticatedContext, String approvedContent) {
        var guarded = new ApprovedMemoryTool(agent.getWorkspaceManager(), authenticatedContext, approvedContent);
        agent.getToolkit().removeTool("memory_save");
        agent.getToolkit().registerTool(guarded);
    }

    public static final class ApprovedMemoryTool {
        private final io.agentscope.harness.agent.tool.MemorySaveTool save;
        private final String user;
        private final String session;
        private final String approved;
        private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        public ApprovedMemoryTool(io.agentscope.harness.agent.workspace.WorkspaceManager manager,
                RuntimeContext context, String approvedContent) {
            if (approvedContent == null || approvedContent.isBlank()) throw new IllegalArgumentException("没有已批准的记忆内容");
            save = new io.agentscope.harness.agent.tool.MemorySaveTool(manager);
            user = java.util.Objects.requireNonNull(context.getUserId());
            session = java.util.Objects.requireNonNull(context.getSessionId());
            approved = approvedContent;
        }
        @io.agentscope.core.tool.Tool(name = "memory_save", description = "保存后端已批准的项目约定；不要加入授权决定、工具许可或其他内容，额外内容会被拒绝")
        public synchronized ToolResultBlock saveApproved(
                @io.agentscope.core.tool.ToolParam(name = "content", description = "只含用户指定项目约定的Markdown条目") String content,
                RuntimeContext context) {
            if (context == null || !user.equals(context.getUserId()) || !session.equals(context.getSessionId())) {
                throw new SecurityException("保存身份或会话不匹配");
            }
            if (!normalized(approved).equals(normalized(content))) {
                throw new IllegalArgumentException("仅可保存后端批准的约定，不得增加授权等文字。已批准内容：" + approved);
            }
            if (consumed.get()) throw new IllegalStateException("本次保存许可已消费");
            consumed.set(true); // 开始写入即消费，失败也不自动再次追加。
            return ToolResultBlock.text(save.memorySave(context, approved));
        }
        private static String normalized(String content) {
            if (content == null) return "";
            return content.strip().replaceFirst("^[-*]\\s*", "").replaceAll("[\\s，；。、]", "");
        }
    }

    /** 显式SDK工具检查；不经过模型，也不经过ReAct权限引擎，调用方负责授权内容。 */
    public static String invoke(HarnessAgent agent, RuntimeContext ctx, String name, Map<String, Object> input) {
        if (!MEMORY_TOOLS.contains(name)) throw new IllegalArgumentException("未知记忆工具");
        ToolUseBlock use = ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name)
                .input(input).content(JsonUtils.getJsonCodec().toJson(input)).build();
        var result = agent.getToolkit().callTool(ToolCallParam.builder().toolUseBlock(use).runtimeContext(ctx).build()).block();
        if (result == null) throw new IllegalStateException("没有记忆工具返回");
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 开启自动辅助调用时的可选配置；当前create未使用，不会因检查此配置发送请求。 */
    public static MemoryConfig automaticPolicy(Model auxiliaryModel) {
        return MemoryConfig.builder()
                .model(auxiliaryModel)
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                .consolidationMinGap(Duration.ofHours(2))
                .consolidationMaxTokens(4000)
                .build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("参数：sessionId message");
        Path project = Path.of("").toAbsolutePath();
        DemoConfig config = DemoConfig.load(project, System.getenv());
        var ctx = SessionStateDemo.context("demo-user", args[0]);
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        Path ownedWorkspace = project.resolve("target/file-memory-05/demo-user");
        try (var agent = create(DemoModels.create(config), ownedWorkspace)) {
            var reply = agent.call(message, ctx).timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (reply != null) System.out.println(reply.getTextContent());
        }
    }
}
