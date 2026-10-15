package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import dev.yiyan.agentscope.session.SessionStateCheck;
import dev.yiyan.agentscope.session.SessionStateDemo;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class SessionStateDemoTest {
    @TempDir Path root;

    /** 固定响应仅用于验证框架生命周期，不模拟真实模型理解或回答能力。 */
    static final class FixtureModel implements Model {
        final List<List<Msg>> inputs = new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("fixture-reply").build())).build());
        }
        @Override public String getModelName() { return "deterministic-framework-fixture"; }
    }

    @Test void successfulCallPersistsInputAndReplyAutomatically() {
        var ctx = SessionStateDemo.context("user-a", "selection-04");
        try (var agent = SessionStateDemo.create(new FixtureModel(), root)) {
            assertEquals("fixture-reply", agent.call("预算200元，偏好静音无线键盘。", ctx).block(Duration.ofSeconds(5)).getTextContent());
            var state = new JsonFileAgentStateStore(root).get("user-a", "selection-04", "agent_state", AgentState.class).orElseThrow();
            assertTrue(state.getContext().stream().anyMatch(msg -> "预算200元，偏好静音无线键盘。".equals(msg.getTextContent())));
            assertTrue(state.getContext().stream().anyMatch(msg -> "fixture-reply".equals(msg.getTextContent())));
        }
    }

    @Test void sameSessionReceivesHistoryOnNextCall() {
        FixtureModel model = new FixtureModel();
        try (var agent = SessionStateDemo.create(model, root)) {
            var ctx = SessionStateDemo.context("user-a", "selection-04");
            agent.call("预算200元，偏好静音无线键盘。", ctx).block(Duration.ofSeconds(5));
            agent.call("我的预算是多少？", ctx).block(Duration.ofSeconds(5));
            assertTrue(model.inputs.get(1).stream().anyMatch(msg -> "预算200元，偏好静音无线键盘。".equals(msg.getTextContent())));
            assertTrue(model.inputs.get(1).stream().anyMatch(msg -> "我的预算是多少？".equals(msg.getTextContent())));
        }
    }

    @Test void anotherSessionDoesNotReceiveFirstSessionHistory() {
        FixtureModel model = new FixtureModel();
        try (var agent = SessionStateDemo.create(model, root)) {
            agent.call("选购A内容。", SessionStateDemo.context("user-a", "selection-a")).block(Duration.ofSeconds(5));
            agent.call("选购B内容。", SessionStateDemo.context("user-a", "selection-b")).block(Duration.ofSeconds(5));
            assertFalse(model.inputs.get(1).stream().anyMatch(msg -> "选购A内容。".equals(msg.getTextContent())));
            assertEquals(2, new JsonFileAgentStateStore(root).listSessionIds("user-a").size());
        }
    }

    @Test void sameSessionIdForDifferentUsersIsSeparate() {
        FixtureModel model = new FixtureModel();
        try (var agent = SessionStateDemo.create(model, root)) {
            agent.call("用户A资料。", SessionStateDemo.context("user-a", "selection")).block(Duration.ofSeconds(5));
            agent.call("用户B资料。", SessionStateDemo.context("user-b", "selection")).block(Duration.ofSeconds(5));
            assertFalse(model.inputs.get(1).stream().anyMatch(msg -> "用户A资料。".equals(msg.getTextContent())));
            assertTrue(Files.exists(root.resolve("user-a/selection/agent_state.json")));
            assertTrue(Files.exists(root.resolve("user-b/selection/agent_state.json")));
        }
    }

    @Test void freshAgentLoadsStateFromSameDirectory() {
        try (var first = SessionStateDemo.create(new FixtureModel(), root)) {
            first.call("KB100售价149元，预算200元。", SessionStateDemo.context("user-a", "selection")).block(Duration.ofSeconds(5));
        }
        FixtureModel model = new FixtureModel();
        try (var second = SessionStateDemo.create(model, root)) {
            second.call("预算和商品价格呢？", SessionStateDemo.context("user-a", "selection")).block(Duration.ofSeconds(5));
            assertTrue(model.inputs.get(0).stream().anyMatch(msg -> "KB100售价149元，预算200元。".equals(msg.getTextContent())));
        }
    }

    @Test void clearingCacheKeepsDiskState() {
        try (var agent = SessionStateDemo.create(new OfflineModel(), root)) {
            var state = agent.getAgentState("user-a", "selection");
            state.contextMutable().add(new UserMessage("保存资料。"));
            agent.saveAgentState("user-a", "selection");
            agent.clearStateCache("user-a", "selection");
            assertNotSame(state, agent.getAgentState("user-a", "selection"));
            assertEquals("保存资料。", agent.getAgentState("user-a", "selection").getContext().get(0).getTextContent());
        }
    }

    @Test void clearingContextPreservesOtherStateAndOtherSessions() {
        try (var agent = SessionStateDemo.create(new OfflineModel(), root)) {
            var state = agent.getAgentState("user-a", "selection");
            state.contextMutable().add(new UserMessage("清理资料。"));
            state.setSummary("摘要");
            state.getPlanModeContext().setPlanActive(true);
            agent.saveAgentState("user-a", "selection");
            agent.getAgentState("user-a", "other").contextMutable().add(new UserMessage("保留资料。"));
            agent.saveAgentState("user-a", "other");
            agent.clearContext("user-a", "selection");
            var saved = new JsonFileAgentStateStore(root).get("user-a", "selection", "agent_state", AgentState.class).orElseThrow();
            assertTrue(saved.getContext().isEmpty());
            assertEquals("", saved.getSummary());
            assertTrue(saved.getPlanModeContext().isPlanActive());
            assertEquals(1, agent.getAgentState("user-a", "other").getContext().size());
        }
    }

    @Test void independentJvmRestoresManualFixture() throws Exception {
        assertTrue(SessionStateCheck.child("write", root).contains("saved=demo-user/selection-04/agent_state.json"));
        String restored = SessionStateCheck.child("read", root);
        assertTrue(restored.contains(SessionStateCheck.FACT));
        assertTrue(restored.contains("\"contextCount\":1"));
    }

    @Test void rejectsUntrustedPathSegmentsBeforeBuildingContext() {
        for (String invalid : new String[]{"..", ".", "user/other", "a\\b", "", "空格 ID", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> SessionStateDemo.context(invalid, "selection"));
            assertThrows(IllegalArgumentException.class, () -> SessionStateDemo.context("user-a", invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> SessionStateDemo.context(null, "selection"));
    }
}
