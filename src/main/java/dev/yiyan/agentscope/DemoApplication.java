package dev.yiyan.agentscope;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoServer;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class DemoApplication {
    public static void main(String[] args) throws Exception {
        DemoConfig config = DemoConfig.load(Path.of("."), System.getenv());
        DemoServer server = new DemoServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("AgentScope Java Demo: http://127.0.0.1:" + server.port());
        System.out.println(JsonUtils.getJsonCodec().toJson(config.publicConfiguration()));
        new CountDownLatch(1).await();
    }
}
