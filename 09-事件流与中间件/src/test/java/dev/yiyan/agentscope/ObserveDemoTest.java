package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.observe.EventView;
import dev.yiyan.agentscope.observe.ObserveCheck;
import dev.yiyan.agentscope.observe.ObserveDemo;
import dev.yiyan.agentscope.observe.ObserveMiddleware;
import dev.yiyan.agentscope.support.OfflineModel;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ObserveDemoTest {
    private RuntimeContext ctx(boolean allowed) { return ObserveDemo.context("fixture-request-09", allowed); }
    @Test void actualStreamCorrelatesToolStartAndResultAndReturnsFinalMessageBeforeEnd() {
        var model = new ObserveCheck.FixtureModel(); var tools = new ObserveDemo.ProductTools(false); var view = new EventView();
        try (var agent = ObserveDemo.create(model, tools, new ObserveMiddleware())) {
            var events = agent.streamEvents("fixture", ctx(true)).doOnNext(view::accept).collectList().block(Duration.ofSeconds(10));
            assertTrue(events.get(0) instanceof AgentStartEvent); assertTrue(events.get(events.size() - 1) instanceof AgentEndEvent);
            assertTrue(events.get(events.size() - 2) instanceof AgentResultEvent);
            var call = events.stream().filter(ToolCallStartEvent.class::isInstance).map(ToolCallStartEvent.class::cast).findFirst().orElseThrow();
            var end = events.stream().filter(ToolResultEndEvent.class::isInstance).map(ToolResultEndEvent.class::cast).findFirst().orElseThrow();
            assertNotEquals(call.getReplyId(), end.getReplyId()); assertEquals(call.getToolCallId(), end.getToolCallId());
            assertEquals(1, view.tools().size());
            var progress = view.tools().get(new EventView.ToolKey(null, call.getToolCallId()));
            assertEquals(call.getReplyId(), progress.callReplyId()); assertEquals(end.getReplyId(), progress.resultReplyId());
            assertEquals(ToolResultState.SUCCESS, end.getState()); assertEquals(2, model.requests.get()); assertEquals(1, tools.reads.get());
            assertEquals(GenerateReason.MODEL_STOP, view.result().getGenerateReason()); assertTrue(view.ended());
        }
    }
    @Test void finalFilterSuppressesIntermediateTextButKeepsToolEvents() {
        var view = new EventView();
        try (var agent = ObserveDemo.create(new ObserveCheck.FixtureModel(), new ObserveDemo.ProductTools(false), new ObserveMiddleware())) {
            agent.streamEvents("fixture", ctx(true)).doOnNext(view::accept).blockLast(Duration.ofSeconds(10));
            assertEquals("fixture-final", view.text()); assertTrue(view.lines().contains("TOOL_CALL_START"));
            assertTrue(view.lines().contains("TOOL_RESULT_END")); assertNotNull(view.result());
        }
    }
    @Test void creatingFluxDoesNotExecuteItAndOneSubscriptionRunsOnce() {
        var model = new ObserveCheck.FixtureModel(); var tools = new ObserveDemo.ProductTools(false); var trace = new ObserveMiddleware();
        try (var agent = ObserveDemo.create(model, tools, trace)) {
            var flux = agent.streamEvents("fixture", ctx(true));
            assertEquals(0, model.requests.get()); assertEquals(0, tools.reads.get()); assertTrue(trace.entries().isEmpty());
            flux.blockLast(Duration.ofSeconds(10));
            assertEquals(1, trace.entries().stream().filter(e -> e.phase().equals("agent-start")).count());
            assertEquals(2, model.requests.get()); assertEquals(1, tools.reads.get());
        }
    }
    @Test void plainCallUsesSameOnAgentMiddlewareWithoutSeparateStreamSubscription() {
        var trace = new ObserveMiddleware(); var model = new ObserveCheck.FixtureModel();
        try (var agent = ObserveDemo.create(model, new ObserveDemo.ProductTools(false), trace)) {
            assertNotNull(agent.call("fixture", ctx(true)).block(Duration.ofSeconds(10)));
            assertEquals(1, trace.entries().stream().filter(e -> e.phase().equals("agent-start")).count());
            assertEquals(2, trace.entries().stream().filter(e -> e.phase().equals("model")).count());
            assertEquals(1, trace.entries().stream().filter(e -> e.phase().equals("acting")).count());
        }
    }
    @Test void toolErrorRemainsErrorEvenWhenAgentReturnsAndStreamEndsNormally() {
        var view = new EventView(); var tools = new ObserveDemo.ProductTools(true);
        try (var agent = ObserveDemo.create(new ObserveCheck.FixtureModel(), tools, new ObserveMiddleware())) {
            agent.streamEvents("fixture", ctx(true)).doOnNext(view::accept).blockLast(Duration.ofSeconds(10));
            assertTrue(view.ended()); assertNotNull(view.result()); assertEquals(1, view.tools().size());
            assertEquals(ToolResultState.ERROR, view.tools().values().iterator().next().state());
        }
    }
    @Test void applicationGuardStopsBeforeToolWhileRawToolAlsoChecksAuthorization() {
        var tools = new ObserveDemo.ProductTools(false); var trace = new ObserveMiddleware();
        try (var agent = ObserveDemo.create(new ObserveCheck.FixtureModel(), tools, trace)) {
            var error = assertThrows(RuntimeException.class, () -> agent.streamEvents("fixture", ctx(false)).blockLast(Duration.ofSeconds(10)));
            assertTrue(error.toString().contains("授权检查")); assertEquals(0, tools.reads.get());
            assertThrows(SecurityException.class, () -> tools.report(ctx(false))); assertEquals(0, tools.reads.get());
        }
    }
    @Test void sameToolNameDifferentCallIdOrSourceAreNotMerged() {
        var view = new EventView();
        view.accept(new ToolResultEndEvent("reply-1", "call-1", "product_report", ToolResultState.SUCCESS));
        view.accept(new ToolResultEndEvent("reply-2", "call-2", "product_report", ToolResultState.ERROR));
        view.accept(new ToolResultEndEvent("reply-1", "call-1", "product_report", ToolResultState.DENIED).withSource("fixture/source"));
        assertEquals(3, view.tools().size()); // 人工事件关联检查，未启动或转发真实子Agent。
        assertEquals(ToolResultState.SUCCESS, view.tools().get(new EventView.ToolKey(null, "call-1")).state());
        view.accept(new TextBlockDeltaEvent("text-reply-a", "text", "part-a"));
        view.accept(new TextBlockDeltaEvent("text-reply-b", "text", "part-b"));
        view.accept(new TextBlockDeltaEvent("text-reply-a", "text", "-next"));
        assertEquals(2, view.textBlocks().size());
        assertEquals("part-a-next", view.textBlocks().get(new EventView.TextKey("text-reply-a", "text")));
    }
    @Test void traceWhitelistDoesNotCopyPromptToolResultOrPrivateMetadata() {
        var trace = new ObserveMiddleware(); var context = ctx(true); context.put("secretFixture", "PRIVATE-FIXTURE-SECRET");
        try (var agent = ObserveDemo.create(new ObserveCheck.FixtureModel(), new ObserveDemo.ProductTools(false), trace)) {
            agent.streamEvents("PRIVATE-FIXTURE-INPUT", context).blockLast(Duration.ofSeconds(10));
            assertTrue(trace.entries().stream().allMatch(e -> e.requestId().equals("fixture-request-09")));
            assertFalse(trace.entries().toString().contains("PRIVATE-FIXTURE")); assertFalse(trace.entries().toString().contains("静音无线键盘"));
            assertThrows(IllegalArgumentException.class, () -> ObserveDemo.context("line\nbreak", true));
        }
    }
    @Test void higherOrderRunsFirstAndSameOrderKeepsRegistrationForPromptPipeline() {
        var input = new AtomicReference<List<Msg>>();
        Model model = new Model() {
            @Override public String getModelName() { return "fixed-order-fixture"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                input.set(List.copyOf(messages)); return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("fixture").build())).build());
            }
        };
        class Prompt implements MiddlewareBase {
            private final int priority; private final String tag;
            Prompt(int priority, String tag) { this.priority = priority; this.tag = tag; }
            @Override public int order() { return priority; }
            @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) { return Mono.just(prompt + "|" + tag); }
        }
        try (var agent = ReActAgent.builder().name("order-fixture").model(model).sysPrompt("base")
                .middleware(new Prompt(1, "low")).middleware(new Prompt(10, "high-a")).middleware(new Prompt(10, "high-b")).build()) {
            agent.call("fixture", ctx(true)).block(Duration.ofSeconds(10));
            assertEquals("base|high-a|high-b|low", input.get().get(0).getTextContent());
        }
    }
    @Test void cancellationUsesFinallySignalWithoutInventingCompletedBusinessResult() {
        var trace = new ObserveMiddleware();
        try (var agent = ObserveDemo.create(new OfflineModel(), new ObserveDemo.ProductTools(false), trace)) {
            trace.onAgent(agent, ctx(true), new AgentInput(List.of()),
                    input -> Flux.<AgentEvent>just(new AgentStartEvent("s", "r", "fixture")).concatWith(Flux.never()))
                    .take(1).collectList().block(Duration.ofSeconds(5));
            assertTrue(trace.entries().stream().anyMatch(e -> e.phase().equals("agent-end") && e.signal().equals("CANCEL")));
        }
    }
}
