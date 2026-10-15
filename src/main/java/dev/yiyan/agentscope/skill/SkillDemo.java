package dev.yiyan.agentscope.skill;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.session.SessionStateDemo;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** 受信任的工作区技能与只读模拟商品快照。资源读取不会执行脚本或下单。 */
public final class SkillDemo {
    public static final Set<String> TOOLS = Set.of("load_skill_through_path", "reset_equipped_tools", "product_report");
    public static final String REPORT = "来源：mock-products-stock.md，模拟快照。\n"
            + "KB100，静音无线键盘，149元，特点：静音/无线/办公，库存12件。\n"
            + "KB200，轻薄无线键盘，199元，特点：静音/无线/办公，库存0件。\n"
            + "KB300，RGB机械键盘，299元，特点：机械/有线/游戏，库存8件。\n";
    private SkillDemo() {}

    public static final class ProductTools {
        @Tool(name = "product_report", description = "读取模拟商品目录与库存快照", readOnly = true, concurrencySafe = true)
        public ToolResultBlock report() { return ToolResultBlock.text(REPORT); }
    }

    public static Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup("product_tools", "商品与库存查询", false, "product-selection");
        toolkit.registration().tool(new ProductTools()).group("product_tools").apply();
        toolkit.registerMetaTool();
        return toolkit;
    }

    public static HarnessAgent create(Model model, Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("skills/product-selection/SKILL.md"))) {
            throw new IllegalArgumentException("请先安装 skills-07 的模拟技能资料");
        }
        var permission = PermissionContextState.builder();
        for (String name : TOOLS) permission.addAllowRule(name,
                new PermissionRule(name, null, PermissionBehavior.ALLOW, "readonly-demo"));
        HarnessAgent agent = HarnessAgent.builder().name("shopping-skill-assistant")
                .model(model).workspace(root).toolkit(toolkit())
                .filesystem(new LocalFilesystemSpec().project(root).mode(LocalFsMode.ROOTED))
                .skillFilter(SkillFilter.only("product-selection"))
                .stateStore(new InMemoryAgentStateStore()).permissionContext(permission.build())
                .disableMemoryHooks().disableMemoryTools().disableCompaction()
                .disableToolResultEviction().disableShellTool().disableSubagents().disableTranscript()
                .maxIters(8).maxRetries(1).build();
        // 2.0.3会复制Toolkit，管理工具必须绑定Agent实际使用的那份组管理器。
        agent.getToolkit().removeTool("reset_equipped_tools");
        agent.getToolkit().registerMetaTool();
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) {
            if (!TOOLS.contains(name)) agent.getToolkit().removeTool(name);
        }
        return agent;
    }

    /** 只安装缺失的自制资料，不覆盖运行目录中已有文件。 */
    public static void install(Path fixtures, Path workspace) throws Exception {
        for (String file : Set.of("AGENTS.md", "skills/product-selection/SKILL.md", "skills/product-selection/references/selection-template.md")) {
            Path target = workspace.resolve(file);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) Files.copy(fixtures.resolve(file), target);
        }
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        Path workspace = project.resolve("target/skills-07/demo-user");
        install(project.resolve("fixtures/skills-07"), workspace);
        var config = DemoConfig.load(project, System.getenv());
        try (var agent = create(DemoModels.create(config), workspace)) {
            var context = SessionStateDemo.context("demo-user", "skills-07");
            var result = agent.call("按商品选购技能推荐200元以内的静音现货键盘，保留价格、库存快照、理由和来源。", context)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (result != null) System.out.println(result.getTextContent());
        }
    }
}
