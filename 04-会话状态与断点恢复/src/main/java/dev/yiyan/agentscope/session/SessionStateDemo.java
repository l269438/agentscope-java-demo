package dev.yiyan.agentscope.session;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;

/** 会话存储示例；身份由后端确定，本入口固定使用演示用户。 */
public final class SessionStateDemo {
    private SessionStateDemo() {}

    public static ReActAgent create(Model model, Path stateDirectory) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(stateDirectory);
        return ReActAgent.builder()
                .name("shopping-session-assistant")
                .sysPrompt("根据当前会话中的模拟选购资料回答，保留预算和偏好。历史库存是快照，不代表实时可售，不下单、不付款。")
                .model(model)
                .stateStore(store)
                .maxIters(8).maxRetries(1)
                .build();
    }

    public static RuntimeContext context(String authenticatedUserId, String sessionId) {
        return RuntimeContext.builder()
                .userId(requireSafeId(authenticatedUserId))
                .sessionId(requireSafeId(sessionId))
                .build();
    }

    private static String requireSafeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("演示身份与会话ID只能使用1—64位字母、数字、下划线或连字符");
        }
        return value;
    }

    /** 显式运行才调用已配置的模型；每次提交一个新消息，历史由 SDK 加载。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("参数：sessionId message；示例：selection-04 我的预算是多少");
        }
        Path project = Path.of("").toAbsolutePath();
        DemoConfig config = DemoConfig.load(project, System.getenv());
        RuntimeContext ctx = context("demo-user", args[0]);
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        try (ReActAgent agent = create(DemoModels.create(config), project.resolve("target/session-state-04"))) {
            Msg reply = agent.call(message, ctx)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds())).block();
            if (reply != null) System.out.println(reply.getTextContent());
        }
    }
}
