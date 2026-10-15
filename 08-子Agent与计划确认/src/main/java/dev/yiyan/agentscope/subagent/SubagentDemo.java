package dev.yiyan.agentscope.subagent;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.skill.SkillDemo;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/** 声明两种任务角色；默认入口只检查配置，显式--run才使用模型并可能委派任务。 */
public final class SubagentDemo {
    public static final Set<String> TOOLS = Set.of("product_report", "plan_enter", "plan_write", "plan_exit",
            "agent_spawn", "agent_send", "agent_list", "task_output", "task_list", "wait_async_results");
    private SubagentDemo() {}

    public static List<SubagentDeclaration> declarations() {
        var extractor = SubagentDeclaration.builder().name("product-finder")
                .description("按预算和特点筛选模拟商品，保留编号、价格和来源")
                .inlineAgentsBody("调用product_report读取模拟商品。按任务预算与特点筛选候选商品，保留编号、价格、特点和来源。不锁库、不下单、不付款。")
                .workspaceMode(WorkspaceMode.ISOLATED).tools(List.of("product_report"))
                .steps(6).inheritParentPermissions(true).build();
        var reviewer = SubagentDeclaration.builder().name("stock-reviewer")
                .description("核对候选商品的模拟库存，排除无库存商品并保留来源")
                .inlineAgentsBody("调用product_report读取库存快照。复核任务参数中的候选商品，只保留库存大于0的商品；缺少库存写待确认，不猜测。标注模拟快照来源，不锁库、不下单。")
                .workspaceMode(WorkspaceMode.ISOLATED).tools(List.of("product_report"))
                .steps(6).inheritParentPermissions(true).build();
        return List.of(extractor, reviewer);
    }

    public static RuntimeContext context(String authenticatedUser, String session) {
        if (authenticatedUser == null || !authenticatedUser.matches("[A-Za-z0-9_-]{1,64}")
                || session == null || !session.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("身份与会话ID须为1—64位安全字符");
        }
        return RuntimeContext.builder().userId(authenticatedUser).sessionId(session)
                .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 30).build();
    }

    public static HarnessAgent create(Model model, Path workspace, String session) {
        context("demo-user", session);
        Path root = workspace.toAbsolutePath().normalize();
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(new SkillDemo.ProductTools());
        var permission = PermissionContextState.builder();
        for (String name : TOOLS) permission.addAllowRule(name,
                new PermissionRule(name, null, PermissionBehavior.ALLOW, "demo-read-and-plan"));
        permission.addAskRule("plan_exit",
                new PermissionRule("plan_exit", null, PermissionBehavior.ASK, "approve-original-plan"));
        // DENY在自动子Agent创建时传播；工具继承白名单无法代替这些执行权限。
        for (String name : List.of("web_fetch", "web_search", "execute", "execute_shell_command",
                "write_file", "edit_file", "memory_save", "memory_consolidate", "skill_manage", "agent_create")) {
            permission.addDenyRule(name, new PermissionRule(name, null, PermissionBehavior.DENY, "closed-demo"));
        }
        HarnessAgent agent = HarnessAgent.builder().name("shopping-orchestrator")
                .model(model).workspace(root).toolkit(toolkit).subagents(declarations())
                .filesystem(new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED))
                .enablePlanMode().planFileDirectory("plans/" + session)
                .stateStore(new InMemoryAgentStateStore()).permissionContext(permission.build())
                .sysPrompt("先阅读模拟商品、写计划并请求批准。批准后先委派product-finder按200元预算和静音要求筛选，再把候选商品交给stock-reviewer核查库存；汇总现货推荐与排除理由。只用这两个角色，不锁库、不下单、不付款。")
                .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                .disableDynamicSkills().disableDynamicSubagents().disableCompaction()
                .disableToolResultEviction().disableTranscript().maxIters(12).maxRetries(1).build();
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!TOOLS.contains(name)) agent.getToolkit().removeTool(name);
        }
        return agent;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { SubagentCheck.main(args); return; }
        if (args.length != 1 || !"--run".equals(args[0])) throw new IllegalArgumentException("参数：--run；默认只检查配置");
        Path project = Path.of("").toAbsolutePath();
        var config = DemoConfig.load(project, System.getenv());
        Path workspace = Files.createTempDirectory(project.resolve("target"), "subagents-08-run-");
        var ctx = context("demo-user", "subagents-08");
        try (var agent = create(DemoModels.create(config), workspace, ctx.getSessionId())) {
            agent.enterPlanMode(ctx);
            Msg reply = agent.call("推荐200元以内的静音现货键盘，先让我查看筛选和库存核查计划。", ctx)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            var gate = new PlanApprovalGate(ctx, Clock.systemUTC());
            var preview = gate.capture(reply);
            String path = agent.getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId())
                    .getPlanModeContext().getCurrentPlanFile();
            System.out.println(agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(ctx, path));
            System.out.println("批准以上计划并开始委派？仅输入yes才批准：");
            boolean approved = new Scanner(System.in).nextLine().trim().equals("yes");
            Msg result = agent.call(List.of(gate.message(ctx, preview.ticket(), approved)), ctx)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (result != null) System.out.println(result.getTextContent());
        }
    }
}
