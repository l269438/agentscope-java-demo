package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.skill.SkillCheck;
import dev.yiyan.agentscope.skill.SkillDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.ToolGroupScope;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillDemoTest {
    @TempDir Path root;
    private Path prepare() throws Exception {
        Path workspace = root.resolve("workspace");
        SkillDemo.install(Path.of("fixtures/skills-07"), workspace); return workspace;
    }
    private RuntimeContext context(String user) { return RuntimeContext.builder().userId(user).sessionId("skills-07").build(); }
    private HarnessSkillMiddleware catalog(HarnessAgent agent, RuntimeContext context, SkillFilter filter) {
        var repo = new WorkspaceSkillRepository(agent.getWorkspaceManager().getFilesystem(), "skills", () -> context);
        var middleware = new HarnessSkillMiddleware(List.of(repo), agent.getToolkit(), filter);
        middleware.onSystemPrompt(agent.getDelegate(), context, "base").block(); return middleware;
    }
    private String load(HarnessAgent agent, RuntimeContext context, String id, String path) {
        return SkillCheck.text(SkillCheck.invoke(agent.getToolkit(), context, "load_skill_through_path", Map.of("skillId", id, "path", path)));
    }
    @Test void initialSchemaKeepsLoaderAndMetaButHidesGroupedReport() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            assertEquals(SkillDemo.TOOLS, agent.getToolkit().getToolNames());
            assertEquals(List.of("load_skill_through_path", "reset_equipped_tools"),
                    agent.getToolkit().getToolSchemas().stream().map(t -> t.getName()).sorted().toList());
            assertFalse(agent.getToolkit().getToolNames().contains("execute_shell_command"));
        }
    }
    @Test void repositoryLoadsInstructionsAndLazilyReadsReference() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var ctx = context("alice");
            var repo = new WorkspaceSkillRepository(agent.getWorkspaceManager().getFilesystem(), "skills", () -> ctx);
            var skills = repo.getAllSkills(ctx); assertEquals(1, skills.size());
            assertTrue(skills.get(0).getResources().isEmpty()); assertFalse(repo.isWriteable());
            assertTrue(repo.resourcesFor("product-selection", ctx).read("references/selection-template.md").orElseThrow().contains("库存快照"));
        }
    }
    @Test void actualAgentRunsFixedLoaderReferenceActivationAndReportChain() throws Exception {
        var model = new SkillCheck.FixtureModel();
        try (var agent = SkillDemo.create(model, prepare())) {
            assertEquals("fixture-skills-finished", agent.call("fixture", context("alice")).block(Duration.ofSeconds(10)).getTextContent());
            assertEquals(5, model.inputs.size());
            assertFalse(model.inputs.get(0).get(0).getTextContent().contains("输出资料来源"));
            assertFalse(model.schemas.get(2).contains("product_report")); assertTrue(model.schemas.get(3).contains("product_report"),
                    model.inputs.get(4).stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).map(SkillCheck::text).toList().toString());
            var results = model.inputs.get(4).stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).toList();
            assertTrue(results.stream().map(SkillCheck::text).anyMatch(s -> s.contains("Name: product-selection") && s.contains("输出资料来源")), results.stream().map(SkillCheck::text).toList().toString());
            assertTrue(results.stream().map(SkillCheck::text).anyMatch(s -> s.contains("库存为0时不得标成现货")));
            assertTrue(results.stream().filter(b -> "product_report".equals(b.getName())).map(SkillCheck::text).anyMatch(SkillDemo.REPORT::equals));
        }
    }
    @Test void filteredCatalogCannotLoadHiddenSkill() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var ctx = context("alice"); catalog(agent, ctx, SkillFilter.none());
            assertTrue(load(agent, ctx, "product-selection_workspace", "SKILL.md").contains("Skill not found"));
        }
    }
    @Test void missingResourceReturnsAvailablePaths() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var ctx = context("alice"); var mw = catalog(agent, ctx, SkillFilter.all());
            String id = mw.runtime().currentCatalog(ctx).ids().get(0);
            String output = load(agent, ctx, id, "missing.md");
            assertTrue(output.contains("Resource not found")); assertTrue(output.contains("references/selection-template.md"));
        }
    }
    @Test void resourceTraversalAndAbsolutePathCannotExposeOutsideFile() throws Exception {
        Files.writeString(root.resolve("outside.md"), "OUTSIDE-SECRET-FIXTURE");
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var ctx = context("alice"); String id = catalog(agent, ctx, SkillFilter.all()).runtime().currentCatalog(ctx).ids().get(0);
            for (String path : List.of("../../outside.md", root.resolve("outside.md").toString())) {
                String output = load(agent, ctx, id, path); assertTrue(output.contains("Resource not found")); assertFalse(output.contains("OUTSIDE-SECRET-FIXTURE"));
            }
        }
    }
    @Test void skillLoadingAloneDoesNotActivateBoundGroup() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var ctx = context("alice"); String id = catalog(agent, ctx, SkillFilter.all()).runtime().currentCatalog(ctx).ids().get(0);
            assertTrue(load(agent, ctx, id, "SKILL.md").contains("Successfully loaded"));
            assertFalse(agent.getToolkit().getActiveGroups().contains("product_tools"));
        }
    }
    @Test void metaReplacementPreservesExternalGroupsAndRejectsUnknownGroup() {
        var toolkit = SkillDemo.toolkit(); toolkit.createToolGroup("other", "其他只读组", true);
        toolkit.createToolGroup("server", "后端管理", true, ToolGroupScope.EXTERNAL);
        var ctx = context("alice");
        SkillCheck.invoke(toolkit, ctx, "reset_equipped_tools", Map.of("to_activate", List.of("product_tools")));
        assertEquals(java.util.Set.of("product_tools", "server"), java.util.Set.copyOf(toolkit.getActiveGroups()));
        assertTrue(SkillCheck.text(SkillCheck.invoke(toolkit, ctx, "reset_equipped_tools", Map.of("to_activate", List.of("MISSING")))).startsWith("Error:"));
        assertEquals(java.util.Set.of("product_tools", "server"), java.util.Set.copyOf(toolkit.getActiveGroups()));
    }
    @Test void currentCallCatalogIsIndependentOfOtherUserContext() throws Exception {
        try (var agent = SkillDemo.create(new OfflineModel(), prepare())) {
            var alice = context("alice"); var bob = context("bob");
            var mw = catalog(agent, alice, SkillFilter.all()); String id = mw.runtime().currentCatalog(alice).ids().get(0);
            assertTrue(load(agent, bob, id, "SKILL.md").contains("Skill not found"));
            assertTrue(load(agent, alice, id, "SKILL.md").contains("Successfully loaded"));
        }
    }
    @Test void inactiveGroupRejectsDirectToolkitCallsUntilActivated() {
        var toolkit = SkillDemo.toolkit();
        assertFalse(toolkit.getToolSchemas().stream().anyMatch(t -> t.getName().equals("product_report")));
        assertTrue(SkillCheck.text(SkillCheck.invoke(toolkit, context("alice"), "product_report", Map.of())).contains("Unauthorized tool call"));
        toolkit.updateToolGroups(List.of("product_tools"), true);
        assertEquals(SkillDemo.REPORT, SkillCheck.text(SkillCheck.invoke(toolkit, context("alice"), "product_report", Map.of())));
    }
}
