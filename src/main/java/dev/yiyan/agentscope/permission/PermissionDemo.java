package dev.yiyan.agentscope.permission;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

/** 模型入口默认查询；--save 请求保存模拟选购清单，--maintenance 禁止保存。 */
public final class PermissionDemo {
    private PermissionDemo() {}

    public static ReActAgent create(Model model, SelectionTools tools, boolean savingDisabled) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return ReActAgent.builder().name("shopping-selection-assistant")
                .sysPrompt("使用工具查询模拟商品和保存选购清单。查询不会保存清单。"
                        + "只有save_shortlist返回成功，才可说模拟选购清单已保存。保存清单不锁库、不下单、不付款。")
                .model(model).toolkit(toolkit).maxIters(8).maxRetries(1)
                .permissionContext(ToolPolicy.create(savingDisabled))
                .stateStore(new InMemoryAgentStateStore()).build();
    }

    public static void main(String[] args) throws Exception {
        DemoConfig config = DemoConfig.load(Path.of(""), System.getenv());
        boolean save = List.of(args).contains("--save");
        boolean maintenance = List.of(args).contains("--maintenance");
        Clock clock = Clock.system(SelectionTools.ZONE);
        var context = RuntimeContext.builder().userId("alice").sessionId("selection-demo").build();
        var gate = new ConfirmationGate(context, clock);
        String input = save ? "把KB100保存到我的模拟选购清单，备注：办公备选。"
                : "KB100的价格和库存是多少？";
        try (var agent = create(DemoModels.create(config), new SelectionTools(), maintenance)) {
            Msg result = agent.call(input, context).block(Duration.ofSeconds(config.timeoutSeconds()));
            Scanner console = new Scanner(System.in);
            for (int round = 0; result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING; round++) {
                if (round >= 3) throw new IllegalStateException("确认轮数已达上限");
                var preview = gate.capture(result);
                System.out.println("当前用户：" + context.getUserId() + "；确认截止：" + preview.expiresAt());
                preview.operations().forEach(t -> System.out.println(t.getName() + " " + t.getInput()));
                System.out.print("批准所展示的模拟操作？输入yes确认，其他输入拒绝：");
                boolean approved = console.hasNextLine() && "yes".equals(console.nextLine().trim());
                Msg confirmation = gate.confirmationMessage(context, preview.ticket(), approved);
                result = agent.call(List.of(confirmation), context).block(Duration.ofSeconds(config.timeoutSeconds()));
            }
            if (result != null) System.out.println(result.getTextContent());
        }
    }
}
