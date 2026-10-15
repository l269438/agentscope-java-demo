package dev.yiyan.agentscope.sandbox;

import dev.yiyan.agentscope.skill.SkillCheck;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.sandbox.*;
import io.agentscope.harness.agent.sandbox.impl.docker.*;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 显式有界Docker集成：本案例临时容器无挂载/网络，仅执行仓库中的固定预览脚本。 */
public final class SandboxDockerCheck {
    private SandboxDockerCheck() {}
    public record DockerOutput(int code, String text) {}
    public static DockerOutput docker(String... args) throws Exception {
        var command = new ArrayList<String>(); command.add("docker"); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Docker检查超时"); }
        return new DockerOutput(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    public static final class TrackingClient implements SandboxClient<DockerSandboxClientOptions> {
        private final DockerSandboxClient delegate = new DockerSandboxClient();
        public final List<Sandbox> opened = new ArrayList<>();
        @Override public Sandbox create(WorkspaceSpec workspace, SandboxSnapshotSpec snapshot, DockerSandboxClientOptions options) {
            Sandbox sandbox = delegate.create(workspace, snapshot, options); opened.add(sandbox); return sandbox;
        }
        @Override public Sandbox resume(SandboxState state) { Sandbox sandbox = delegate.resume(state); opened.add(sandbox); return sandbox; }
        @Override public void delete(Sandbox sandbox) { delegate.delete(sandbox); }
        @Override public String serializeState(SandboxState state) { return delegate.serializeState(state); }
        @Override public SandboxState deserializeState(String json) { return delegate.deserializeState(json); }
        @Override public SandboxState deserializeState(String json, SandboxSnapshotSpec spec) { return delegate.deserializeState(json, spec); }
    }
    private static String lastRead(SandboxCheck.FixtureModel model) {
        String raw = model.inputs.get(model.inputs.size() - 1).stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(b -> "read_file".equals(b.getName())).map(SkillCheck::text).reduce((a,b) -> b).orElseThrow();
        return io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(raw, String.class);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].startsWith("sha256:")) throw new IllegalArgumentException("请传已准备镜像的完整sha256 ID；本检查不拉取镜像");
        require(docker("image", "inspect", args[0], "--format", "{{.Id}}").code() == 0, "镜像不存在");
        Path project = Path.of("").toAbsolutePath(); Files.createDirectories(project.resolve("target"));
        Path root = Files.createTempDirectory(project.resolve("target"), "sandbox-check-10-");
        var client = new TrackingClient(); var model = new SandboxCheck.FixtureModel();
        SandboxDemo.install(project.resolve("fixtures/sandbox-10"), root.resolve("host-workspace"));
        var fs = SandboxDemo.filesystem(args[0], root.resolve("snapshots"), new WorkspaceSpec(), client);
        var inspections = new ArrayList<String>();
        var inspectMiddleware = new io.agentscope.core.middleware.MiddlewareBase() {
            @Override public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onModelCall(
                    io.agentscope.core.agent.Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                    io.agentscope.core.middleware.ModelCallInput input,
                    java.util.function.Function<io.agentscope.core.middleware.ModelCallInput, reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>> next) {
                try {
                    var state = (DockerSandboxState) client.opened.get(client.opened.size() - 1).getState();
                    String inspection = docker("inspect", state.getContainerId(), "--format",
                            "network={{.HostConfig.NetworkMode}}; memory={{.HostConfig.Memory}}; cpu={{.HostConfig.NanoCpus}}; readonly={{.HostConfig.ReadonlyRootfs}}; mounts={{len .Mounts}}; user={{.Config.User}}; pids={{.HostConfig.PidsLimit}}; caps={{json .HostConfig.CapDrop}}; security={{json .HostConfig.SecurityOpt}}").text().strip();
                    if (!inspections.contains(inspection)) inspections.add(inspection);
                    require(inspection.contains("network=none; memory=268435456; cpu=1000000000; readonly=true; mounts=0; user=65534:65534; pids=64"), "容器配置不匹配：" + inspection);
                    require(inspection.contains("ALL") && inspection.contains("no-new-privileges"), "容器权限未收紧");
                } catch (Exception e) { return reactor.core.publisher.Flux.error(e); }
                return next.apply(input);
            }
        };
        System.out.println("scriptedModel=true; externalModelRequests=0; spawnedSubagents=0; image=" + args[0]);
        try (var agent = SandboxDemo.create(model, root.resolve("host-workspace"), fs, inspectMiddleware)) {
            Msg first = agent.call("fixture-create-preview", SandboxDemo.context("alice", "sandbox10-a")).block(Duration.ofSeconds(45));
            require(first != null && "fixture-sandbox-finished".equals(first.getTextContent()), "首轮未结束");
            String initial = lastRead(model);
            require(initial.contains("KB100 静音无线键盘 | 149 | 12 | 推荐") && initial.contains("KB200 轻薄无线键盘 | 199 | 0 | 无库存") && initial.contains("KB300 RGB机械键盘 | 299 | 8 | 超预算"), "预览缺失事实：" + initial);
            require(!Files.exists(root.resolve("host-workspace/selection-preview.md")), "输出错误写到了宿主工作区");
            agent.call("fixture-read-after-release", SandboxDemo.context("alice", "sandbox10-a")).block(Duration.ofSeconds(45));
            require(initial.equals(lastRead(model)), "相同会话快照恢复不一致");
            agent.call("fixture-other-session", SandboxDemo.context("bob", "sandbox10-b")).block(Duration.ofSeconds(45));
            require(lastRead(model).startsWith("Error:"), "另一会话读取到了预览：" + lastRead(model));
            require(client.opened.size() == 3, "容器调用数不匹配");
            String a = ((DockerSandboxState) client.opened.get(0).getState()).getContainerId();
            String resumed = ((DockerSandboxState) client.opened.get(1).getState()).getContainerId();
            require(!a.equals(resumed), "本版本自管容器未按调用释放/重建");
            require(client.opened.get(0).getState().getSessionId().equals(client.opened.get(1).getState().getSessionId()), "快照ID未延续");
            for (var sandbox : client.opened) require(docker("inspect", ((DockerSandboxState) sandbox.getState()).getContainerId()).code() != 0, "临时容器未清理");
            inspections.forEach(System.out::println);
            System.out.println("initial-preview:\n" + initial);
            System.out.println("same-session-snapshot=PASS; distinct-session=PASS; host-output-absent=PASS; containers-created=3; containers-remaining=0");
            System.out.println("snapshots=" + root.resolve("snapshots"));
            System.out.println("sandbox-docker=PASS");
        } finally {
            for (var sandbox : client.opened) {
                var state = (DockerSandboxState) sandbox.getState();
                if (state.getContainerId() != null && docker("inspect", state.getContainerId()).code() == 0) sandbox.shutdown();
            }
        }
    }
}
