package dev.yiyan.agentscope.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/** 固定请求验证Skill/资源/工具组的真实SDK路径，不评估LLM技能选择和清单质量。 */
public final class SkillCheck {
    private SkillCheck() {}
    public static String text(ToolResultBlock result) {
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).collect(java.util.stream.Collectors.joining("\n"));
    }
    public static ToolResultBlock invoke(Toolkit toolkit, RuntimeContext context, String name, Map<String, Object> input) {
        var use = ToolUseBlock.builder().id("manual-" + UUID.randomUUID()).name(name).input(input)
                .content(JsonUtils.getJsonCodec().toJson(input)).build();
        return toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).runtimeContext(context).build())
                .block(Duration.ofSeconds(5));
    }

    public static final class FixtureModel implements Model {
        public final List<List<Msg>> inputs = new ArrayList<>();
        public final List<List<String>> schemas = new ArrayList<>();
        public String skillId;
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int step = inputs.size(); inputs.add(List.copyOf(messages));
            schemas.add(tools.stream().map(ToolSchema::getName).sorted().toList());
            if (step == 0) {
                String prompt = messages.get(0).getTextContent();
                int catalog = prompt.indexOf("<available_skills>");
                if (catalog < 0) return Flux.error(new IllegalStateException("缺少技能目录段"));
                var found = Pattern.compile("<skill-id>([^<]+)</skill-id>").matcher(prompt.substring(catalog));
                if (!found.find()) return Flux.error(new IllegalStateException("缺少技能目录"));
                skillId = found.group(1);
            }
            String name;
            Map<String, Object> input;
            switch (step) {
                case 0 -> { name = "load_skill_through_path"; input = Map.of("skillId", skillId, "path", "SKILL.md"); }
                case 1 -> { name = "load_skill_through_path"; input = Map.of("skillId", skillId, "path", "references/selection-template.md"); }
                case 2 -> { name = "reset_equipped_tools"; input = Map.of("to_activate", List.of("product_tools")); }
                case 3 -> { name = "product_report"; input = Map.of(); }
                default -> { return Flux.just(ChatResponse.builder().content(List.<ContentBlock>of(
                        TextBlock.builder().text("fixture-skills-finished").build())).build()); }
            }
            return Flux.just(ChatResponse.builder().content(List.<ContentBlock>of(ToolUseBlock.builder()
                    .id("fixture-skill-" + step).name(name).input(input)
                    .content(JsonUtils.getJsonCodec().toJson(input)).build())).build());
        }
        @Override public String getModelName() { return "deterministic-skill-fixture"; }
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        Path workspace = project.resolve("target/local-checks/skills-07/" + UUID.randomUUID());
        SkillDemo.install(project.resolve("07-Skills与工具组/fixtures/skills-07"), workspace);
        var model = new FixtureModel();
        try (var agent = SkillDemo.create(model, workspace)) {
            var result = agent.call("fixture-request", RuntimeContext.builder().userId("demo-user").sessionId("skills-07").build())
                    .block(Duration.ofSeconds(10));
            if (result == null || !"fixture-skills-finished".equals(result.getTextContent())) throw new IllegalStateException("固定路径未结束");
            if (model.schemas.get(0).contains("product_report") || !model.schemas.get(3).contains("product_report")) {
                throw new IllegalStateException("工具组Schema没有按需变化");
            }
            String report = model.inputs.get(4).stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(b -> "product_report".equals(b.getName())).map(SkillCheck::text).findFirst().orElseThrow();
            if (!SkillDemo.REPORT.equals(report)) throw new IllegalStateException("商品快照不一致");
            System.out.println("scriptedModel=true; externalModelRequests=0");
            System.out.println("skillId=" + model.skillId + "; resource=references/selection-template.md");
            System.out.println("initialSchemas=" + model.schemas.get(0));
            System.out.println("activatedSchemas=" + model.schemas.get(3));
            System.out.println("report-match=true; skills-groups=PASS");
        }
    }
}
