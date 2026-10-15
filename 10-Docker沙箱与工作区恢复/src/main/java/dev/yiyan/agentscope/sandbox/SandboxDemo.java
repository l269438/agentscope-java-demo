package dev.yiyan.agentscope.sandbox;

import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.DemoModels;
import dev.yiyan.agentscope.session.SessionStateDemo;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** 有界商品CSV选购预览；无宿主bind mount/网络/子Agent，仅显式入口才创建Docker或LLM。 */
public final class SandboxDemo {
    public static final String COMMAND = "python3 build_preview.py";
    public static final Set<String> TOOLS = Set.of("execute", "read_file");
    public static final long MEMORY = 256 * 1024 * 1024L;
    private SandboxDemo() {}

    /** 固定资料安装到新工作区；存在且不同的文件拒绝覆盖。Docker投影仅包含这两个文件。 */
    public static void install(Path fixtures, Path hostWorkspace) throws Exception {
        Files.createDirectories(hostWorkspace);
        for (String name : Set.of("products.csv", "build_preview.py")) {
            Path target = hostWorkspace.resolve(name);
            if (Files.exists(target)) {
                if (!Files.readString(target).equals(Files.readString(fixtures.resolve(name)))) throw new IllegalArgumentException("已有资料不同，拒绝覆盖：" + target);
            } else Files.copy(fixtures.resolve(name), target);
        }
    }

    public static SandboxFilesystemSpec filesystem(String image, Path snapshots, WorkspaceSpec workspace, SandboxClient<?> client) {
        if (image == null || image.isBlank() || image.contains("\n")) throw new IllegalArgumentException("镜像不能为空或含换行");
        return new DockerFilesystemSpec().image(image).workspaceRoot("/workspace")
                .memorySizeBytes(MEMORY).cpuCount(1L).network("none")
                .additionalRunArgs("--entrypoint=", "--read-only", "--user=65534:65534", "--cap-drop=ALL",
                        "--security-opt=no-new-privileges", "--pids-limit=64", "--memory-swap=" + MEMORY,
                        "--tmpfs=/workspace:rw,size=67108864,uid=65534,gid=65534,mode=0700",
                        "--tmpfs=/tmp:rw,size=16777216,uid=65534,gid=65534,mode=0700")
                .workspaceSpec(workspace).snapshotSpec(new LocalSnapshotSpec(snapshots)).client(client)
                .workspaceProjectionRoots(java.util.List.of("products.csv", "build_preview.py")).isolationScope(IsolationScope.SESSION);
    }

    public static HarnessAgent create(Model model, Path hostWorkspace, SandboxFilesystemSpec filesystem,
            io.agentscope.core.middleware.MiddlewareBase... additionalMiddleware) {
        var permissions = PermissionContextState.builder();
        for (String name : TOOLS) permissions.addAllowRule(name,
                new PermissionRule(name, null, PermissionBehavior.ALLOW, "bounded-preview"));
        var builder = HarnessAgent.builder().name("shopping-sandbox-assistant").model(model).workspace(hostWorkspace)
                .filesystem(filesystem).stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissions.build()).middleware(new SandboxGuard())
                .sysPrompt("模拟商品CSV已提供。只执行python3 build_preview.py，再读取selection-preview.md并展示选购预览；"
                        + "预算200元、静音办公，只推荐有库存商品。不要修改脚本，不锁库、不下单、不付款。execute必须传timeout=10，无working_directory。")
                .disableSubagents().disableDynamicSkills().disableMemoryTools().disableMemoryHooks()
                .disableCompaction().disableToolResultEviction().disableTranscript().disableToolsConfig()
                .maxIters(6).maxRetries(1);
        for (var middleware : additionalMiddleware) builder.middleware(middleware);
        var agent = builder.build();
        for (String name : Set.copyOf(agent.getToolkit().getToolNames())) if (!TOOLS.contains(name)) agent.getToolkit().removeTool(name);
        return agent;
    }

    /** 调用方必须先校验归属；SESSION键仅使用sessionId，需后端分配全局唯一ID。 */
    public static RuntimeContext context(String authenticatedUser, String serverSessionId) {
        return SessionStateDemo.context(authenticatedUser, serverSessionId);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { SandboxCheck.main(args); return; }
        if (!args[0].equals("--run") || args.length != 2) throw new IllegalArgumentException("显式模型入口：--run 已准备好的镜像ID或tag");
        Path project = Path.of("").toAbsolutePath(); Path root = Files.createTempDirectory(project.resolve("target"), "sandbox-model-10-");
        var config = DemoConfig.load(project, System.getenv());
        install(project.resolve("10-Docker沙箱与工作区恢复/fixtures/sandbox-10"), root.resolve("host-workspace"));
        var fs = filesystem(args[1], root.resolve("snapshots"), new WorkspaceSpec(), null);
        try (var agent = create(DemoModels.create(config), root.resolve("host-workspace"), fs)) {
            var result = agent.call("处理模拟商品CSV，展示200元以内的静音现货键盘选购预览。", context("demo-user", UUID.randomUUID().toString()))
                    .block(Duration.ofSeconds(config.timeoutSeconds()));
            if (result != null) System.out.println(result.getTextContent());
        }
        System.out.println("workspaceSnapshots=" + root.resolve("snapshots"));
    }
}
