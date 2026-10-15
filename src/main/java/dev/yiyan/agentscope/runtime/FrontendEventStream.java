package dev.yiyan.agentscope.runtime;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** 只转发应用可见执行事件，不读取旧日志、不转发思维片段。 */
public final class FrontendEventStream {
    private static final Set<String> VISIBLE = Set.of("MODEL_CALL_START", "java_tool_input",
            "java_tool_output", "final_reply", "failure", "run_end");
    private final OutputStream output;
    private boolean connected = true;

    public FrontendEventStream(OutputStream output) { this.output = output; }

    public void accept(Map<String, Object> event) {
        if (VISIBLE.contains(event.get("type"))) send("trace", event);
    }

    public synchronized void send(String name, Object data) {
        if (!connected) return;
        if (!Set.of("run", "trace", "done", "error").contains(name)) {
            throw new IllegalArgumentException("未知前端事件类型");
        }
        String frame = "event: " + name + "\ndata: " + JsonUtils.getJsonCodec().toJson(data) + "\n\n";
        try {
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 浏览器断连不破坏运行原件；任务继续受既有120秒限制，不自动重试。
            connected = false;
        }
    }

    public synchronized boolean connected() { return connected; }
}
