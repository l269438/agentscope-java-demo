package dev.yiyan.agentscope.session;

import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 两个独立 JVM 只检查状态文件，不执行 Agent.call 或模型请求。 */
public final class SessionStateCheck {
    public static final String FACT = "模拟选购：预算200元，偏好静音无线办公键盘；KB100售价149元，库存快照12件；KB200售价199元，库存快照0件。";
    private SessionStateCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Path root = Path.of("target/local-checks/session-04", UUID.randomUUID().toString()).toAbsolutePath();
            Files.createDirectories(root);
            System.out.println("fixture=manual-state; externalModelRequests=0");
            System.out.print(child("write", root));
            System.out.print(child("read", root));
            System.out.println("separate-jvm-roundtrip=PASS");
            return;
        }
        if (args.length != 2 || !List.of("write", "read").contains(args[0])) {
            throw new IllegalArgumentException("仅支持 write/read 和状态目录");
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        var ctx = SessionStateDemo.context("demo-user", "selection-04");
        try (var agent = SessionStateDemo.create(new OfflineModel(), root)) {
            if ("write".equals(args[0])) {
                AgentState state = agent.getAgentState("demo-user", "selection-04");
                state.contextMutable().add(new UserMessage(FACT));
                state.setSummary("人工状态夹具：预算200元，静音无线办公键盘，KB100有现货快照。");
                agent.saveAgentState(ctx);
                System.out.println("saved=demo-user/selection-04/agent_state.json");
            } else {
                JsonFileAgentStateStore store = new JsonFileAgentStateStore(root);
                AgentState restored = store.get("demo-user", "selection-04", "agent_state", AgentState.class)
                        .orElseThrow(() -> new IllegalStateException("没有会话文件"));
                AgentState fromNewAgent = agent.getAgentState("demo-user", "selection-04");
                if (!JsonUtils.getJsonCodec().toJson(restored).equals(JsonUtils.getJsonCodec().toJson(fromNewAgent))) {
                    throw new IllegalStateException("新Agent加载的状态与文件Store不一致");
                }
                if (restored.getContext().size() != 1 ||
                        !FACT.equals(restored.getContext().get(0).getTextContent())) {
                    throw new IllegalStateException("会话内容与人工夹具不一致");
                }
                System.out.println("restored=" + JsonUtils.getJsonCodec().toJson(Map.of(
                        "userId", restored.getUserId(), "sessionId", restored.getSessionId(),
                        "contextCount", restored.getContext().size(), "text", restored.getContext().get(0).getTextContent())));
            }
        }
    }

    public static String child(String action, Path root) throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin/java").toString();
        Process process = new ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"),
                SessionStateCheck.class.getName(), action, root.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("本地状态检查超时");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("子进程失败：" + stdout);
        return stdout;
    }
}
