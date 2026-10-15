package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.sandbox.*;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.*;
import io.agentscope.harness.agent.sandbox.impl.docker.*;
import io.agentscope.harness.agent.sandbox.snapshot.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 纯框架/配置检查不启动Docker、不构建LLM、不启动子Agent。物理隔离另有显式CLI。 */
class SandboxDemoTest {
    @TempDir Path root;
    private SandboxContext context() { return SandboxDemo.filesystem("prepared-image", root.resolve("snapshots"), new WorkspaceSpec(), null).toSandboxContext(); }
    private SandboxIsolationKey key(IsolationScope scope, String user, String sid) {
        return SandboxIsolationKey.resolve(scope, io.agentscope.core.agent.RuntimeContext.builder().userId(user).sessionId(sid).build(), "demo").orElseThrow();
    }
    @Test void explicitSessionAndBoundedDockerOptions() {
        var ctx = context(); var options = (DockerSandboxClientOptions) ctx.getClientOptions();
        assertEquals(IsolationScope.SESSION, ctx.getIsolationScope()); assertEquals("none", options.getNetwork());
        assertEquals(Long.valueOf(SandboxDemo.MEMORY), options.getMemorySizeBytes()); assertEquals(Long.valueOf(1), options.getCpuCount());
        assertEquals(0, options.getExposedPorts().length); assertEquals("/workspace", options.getWorkspaceRoot()); assertTrue(options.getEnvironment().isEmpty());
    }
    @Test void hardeningHasNoHostBindOrDockerSocket() {
        var options = (DockerSandboxClientOptions) context().getClientOptions(); var args = options.getAdditionalRunArgs();
        assertTrue(args.containsAll(List.of("--read-only", "--user=65534:65534", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=64")));
        assertTrue(args.stream().noneMatch(a -> a.startsWith("-v") || a.startsWith("--mount") || a.contains("docker.sock")));
        assertTrue(context().getWorkspaceSpec().getEntries().isEmpty());
    }
    @Test void projectionOnlyIncludesTwoFixedFilesAndInstallRefusesChanges() throws Exception {
        Path host = root.resolve("host"); SandboxDemo.install(Path.of("10-Docker沙箱与工作区恢复/fixtures/sandbox-10"), host);
        var ctx = SandboxDemo.filesystem("prepared",root.resolve("snapshots"),new WorkspaceSpec(),null).toSandboxContext(host);
        var projection = (io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry)ctx.getWorkspaceSpec().getEntries().get("__workspace_projection__");
        assertEquals(List.of("products.csv","build_preview.py"), projection.getIncludeRoots());
        assertEquals(2, WorkspaceProjectionApplier.build(ctx.getWorkspaceSpec()).fileCount());
        SandboxDemo.install(Path.of("10-Docker沙箱与工作区恢复/fixtures/sandbox-10"),host);
        java.nio.file.Files.writeString(host.resolve("products.csv"), "dirty-user-content");
        assertThrows(IllegalArgumentException.class, () -> SandboxDemo.install(Path.of("10-Docker沙箱与工作区恢复/fixtures/sandbox-10"),host));
        assertEquals("dirty-user-content",java.nio.file.Files.readString(host.resolve("products.csv")));
    }
    @Test void defaultIsUserAndFallsBackToSession() {
        assertEquals(IsolationScope.USER, key(null,"alice","sid").getScope());
        assertEquals(key(IsolationScope.USER,"alice","a"), key(IsolationScope.USER,"alice","b"));
        assertEquals(key(IsolationScope.SESSION,null,"sid"), key(IsolationScope.USER,null,"sid"));
    }
    @Test void sessionKeyDoesNotIncludeUserRequiresGloballyUniqueBackendId() {
        assertEquals(key(IsolationScope.SESSION,"alice","same"), key(IsolationScope.SESSION,"bob","same"));
        assertNotEquals(key(IsolationScope.SESSION,"alice","a"), key(IsolationScope.SESSION,"alice","b"));
        assertTrue(SandboxIsolationKey.resolve(IsolationScope.SESSION,null,"demo").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SandboxDemo.context("alice","../bad"));
    }
    @Test void actualSessionStateSlotHasSameCollisionAcrossUsers() throws Exception {
        var store = new SessionSandboxStateStore(new InMemoryAgentStateStore(), "demo");
        store.save(key(IsolationScope.SESSION,"alice","same"), "fixture-state");
        assertEquals("fixture-state",store.load(key(IsolationScope.SESSION,"bob","same")).orElseThrow());
        assertTrue(store.load(key(IsolationScope.SESSION,"bob","different")).isEmpty());
    }
    @Test void defaultNoopDoesNotPersistAndExplicitLocalSnapshotDoes() throws Exception {
        var defaults = new DockerFilesystemSpec().toSandboxContext(); assertInstanceOf(NoopSnapshotSpec.class, defaults.getSnapshotSpec());
        var snapshot = context().getSnapshotSpec().build("safe-snapshot"); assertFalse(snapshot.isRestorable());
        snapshot.persist(new java.io.ByteArrayInputStream("fixture-archive".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (var in = snapshot.restore()) { assertEquals("fixture-archive", new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
        assertTrue(snapshot.isRestorable()); assertThrows(RuntimeException.class, () -> new LocalSnapshotSpec(root).build("../bad"));
    }
    @Test void guardOnlyAllowsSpecificCommandAndReadPath() {
        SandboxGuard.validate(SandboxCheck.call("execute",Map.of("command",SandboxDemo.COMMAND,"timeout",10)));
        SandboxGuard.validate(SandboxCheck.call("read_file",Map.of("path","selection-preview.md")));
        assertThrows(SecurityException.class, () -> SandboxGuard.validate(SandboxCheck.call("execute",Map.of("command","pwd","timeout",10))));
        assertThrows(SecurityException.class, () -> SandboxGuard.validate(SandboxCheck.call("execute",Map.of("command",SandboxDemo.COMMAND,"timeout",30))));
        assertThrows(SecurityException.class, () -> SandboxGuard.validate(SandboxCheck.call("read_file",Map.of("path","/etc/passwd"))));
        assertThrows(SecurityException.class, () -> SandboxGuard.validate(SandboxCheck.call("write_file",Map.of("path","build_preview.py"))));
    }
    @Test void assemblyExposesTwoCaseToolsWithoutStartingSandbox() {
        try (var agent = SandboxDemo.create(new OfflineModel(), root.resolve("host"), SandboxDemo.filesystem("prepared",root.resolve("snapshots"),new WorkspaceSpec(),null))) {
            assertEquals(SandboxDemo.TOOLS, agent.getToolkit().getToolNames());
            assertFalse(agent.getToolkit().getToolNames().contains("agent_spawn"));
        }
    }
    @Test void dockerStateRoundTripPreservesLimitsAndLocalSnapshot() {
        var ctx = context(); var client = new DockerSandboxClient();
        var sandbox = client.create(ctx.getWorkspaceSpec(),ctx.getSnapshotSpec(),(DockerSandboxClientOptions)ctx.getClientOptions());
        var restored = (DockerSandboxState)client.deserializeState(client.serializeState(sandbox.getState()));
        assertEquals("none",restored.getNetwork()); assertEquals(Long.valueOf(SandboxDemo.MEMORY),restored.getMemorySizeBytes());
        assertInstanceOf(LocalSandboxSnapshot.class,restored.getSnapshot()); assertNull(restored.getContainerId());
    }
}
