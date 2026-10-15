package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.react.ProductQueryTools;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductQueryToolsTest {
    private final ProductQueryTools tools = new ProductQueryTools();
    @Test void filtersBudgetAndFeature() {
        assertEquals(List.of("KB100", "KB200"), tools.findProducts(200, "静音").stream().map(p -> p.get("sku")).toList());
        assertEquals(List.of("KB300"), tools.findProducts(300, "游戏").stream().map(p -> p.get("sku")).toList());
    }
    @Test void emptyResults() {
        assertTrue(tools.findProducts(100, "静音").isEmpty());
        assertTrue(tools.findProducts(300, "蓝牙").isEmpty());
    }
    @Test void validatesFilterInput() {
        assertThrows(IllegalArgumentException.class, () -> tools.findProducts(0, "静音"));
        assertThrows(IllegalArgumentException.class, () -> tools.findProducts(-1, "静音"));
        assertThrows(IllegalArgumentException.class, () -> tools.findProducts(200, " "));
        assertThrows(IllegalArgumentException.class, () -> tools.findProducts(200, null));
    }
    @Test void distinguishesStock() {
        assertEquals(Map.of("sku", "KB100", "stock", 12, "inStock", true), tools.getStock("KB100"));
        assertEquals(Map.of("sku", "KB200", "stock", 0, "inStock", false), tools.getStock("KB200"));
    }
    @Test void handlesBoundaryAndWhitespace() {
        assertEquals(1, tools.findProducts(149, " 静音 ").size());
        assertTrue(tools.findProducts(148, "静音").isEmpty());
    }
    @Test void rejectsUnknownAndMissingSku() {
        assertThrows(IllegalArgumentException.class, () -> tools.getStock("MISSING"));
        assertThrows(IllegalArgumentException.class, () -> tools.getStock(" "));
        assertThrows(IllegalArgumentException.class, () -> tools.getStock(null));
    }
    @Test void registersSchemasAndReadOnlyFlags() {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tools);
        assertEquals(Set.of("find_products", "get_stock"), toolkit.getToolNames());
        var schema = toolkit.getToolSchemas().stream().filter(s -> s.getName().equals("find_products")).findFirst().orElseThrow();
        assertEquals(List.of("maxPriceYuan", "feature"), schema.getParameters().get("required"));
        var properties = (Map<?, ?>) schema.getParameters().get("properties");
        assertEquals("integer", ((Map<?, ?>) properties.get("maxPriceYuan")).get("type"));
        assertEquals("string", ((Map<?, ?>) properties.get("feature")).get("type"));
        for (String name : toolkit.getToolNames()) {
            assertTrue(toolkit.getTool(name).isReadOnly());
            assertTrue(((ToolBase) toolkit.getTool(name)).isConcurrencySafe());
        }
    }
    @Test void convertsResultToJsonText() {
        var result = call("get_stock", Map.of("sku", "KB100"));
        Map<?, ?> json = JsonUtils.getJsonCodec().fromJson(text(result), Map.class);
        assertEquals(true, json.get("inStock")); assertEquals(12, json.get("stock"));
    }
    @Test void returnsToolError() { assertTrue(text(call("get_stock", Map.of("sku", "MISSING"))).contains("商品不存在")); }
    @Test void validatesRequiredParameter() { assertTrue(text(call("get_stock", Map.of())).contains("Parameter validation failed")); }
    @Test void preservesCallIdAndName() {
        var result = call("get_stock", Map.of("sku", "KB100"));
        assertEquals("test-call", result.getId()); assertEquals("get_stock", result.getName());
    }
    private ToolResultBlock call(String name, Map<String, Object> input) {
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(tools);
        var use = ToolUseBlock.builder().id("test-call").name(name).input(input)
                .content(JsonUtils.getJsonCodec().toJson(input)).build();
        var results = toolkit.callTools(List.of(use), null, null, null).block();
        assertNotNull(results); return results.get(0);
    }
    private String text(ToolResultBlock result) { return ((TextBlock) result.getOutput().get(0)).getText(); }
}
