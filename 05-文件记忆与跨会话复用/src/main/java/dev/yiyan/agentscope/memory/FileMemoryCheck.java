package dev.yiyan.agentscope.memory;

import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** 每次使用独立UUID工作区，只检查SDK显式记忆工具，没有外部模型请求。 */
public final class FileMemoryCheck {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("target/local-checks/memory-05", UUID.randomUUID().toString(), "demo-user");
        var first = SessionStateDemo.context("demo-user", "selection-a");
        var second = SessionStateDemo.context("demo-user", "selection-b");
        try (var agent = FileMemoryDemo.create(new OfflineModel(), workspace)) {
            System.out.println("tools=" + new TreeSet<>(agent.getToolkit().getToolNames()));
            System.out.println("manual-save=" + FileMemoryDemo.invoke(agent, first, "memory_save", Map.of("content", FileMemoryDemo.FACT)));
        }
        try (var agent = FileMemoryDemo.create(new OfflineModel(), workspace)) {
            String result = FileMemoryDemo.invoke(agent, second, "memory_search", Map.of("query", "选购偏好"));
            if (!result.contains("缺失项标待确认")) throw new IllegalStateException("记忆内容未读回");
            System.out.println("new-session-search=" + result);
            System.out.println("externalModelRequests=0; memory-tools-roundtrip=PASS");
        }
    }
}
