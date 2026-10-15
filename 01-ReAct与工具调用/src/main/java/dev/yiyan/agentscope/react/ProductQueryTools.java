package dev.yiyan.agentscope.react;

import dev.yiyan.agentscope.runtime.RunTrace;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;

public class ProductQueryTools {
    private record Product(String sku, String name, int priceYuan, List<String> features) {}
    private static final List<Product> CATALOG = List.of(
            new Product("KB100", "静音无线键盘", 149, List.of("静音", "无线", "办公")),
            new Product("KB200", "轻薄无线键盘", 199, List.of("静音", "无线", "办公")),
            new Product("KB300", "RGB机械键盘", 299, List.of("机械", "有线", "游戏")));
    private static final Map<String, Integer> STOCK = Map.of("KB100", 12, "KB200", 0, "KB300", 8);
    private final RunTrace trace;

    public ProductQueryTools() { this(null); }
    public ProductQueryTools(RunTrace trace) { this.trace = trace; }

    @Tool(name = "find_products", description = "按预算和商品特点筛选键盘，返回编号、名称、价格与特点；不查询库存",
            readOnly = true, concurrencySafe = true)
    public List<Map<String, Object>> findProducts(
            @ToolParam(name = "maxPriceYuan", description = "预算上限，单位元，必须大于0") int maxPriceYuan,
            @ToolParam(name = "feature", description = "必需特点，例如静音或无线") String feature) {
        log("java_tool_input", Map.of("name", "find_products", "maxPriceYuan", maxPriceYuan,
                "feature", feature == null ? "" : feature));
        if (maxPriceYuan <= 0 || feature == null || feature.isBlank()) {
            throw new IllegalArgumentException("预算必须大于0，商品特点不能为空");
        }
        var result = CATALOG.stream()
                .filter(p -> p.priceYuan() <= maxPriceYuan && p.features().contains(feature.trim()))
                .map(p -> Map.<String, Object>of("sku", p.sku(), "name", p.name(),
                        "priceYuan", p.priceYuan(), "features", p.features())).toList();
        log("java_tool_output", Map.of("name", "find_products", "result", result));
        return result;
    }

    @Tool(name = "get_stock", description = "查询指定商品的模拟可售库存，仅查询、不锁库、不下单",
            readOnly = true, concurrencySafe = true)
    public Map<String, Object> getStock(
            @ToolParam(name = "sku", description = "find_products返回的商品编号") String sku) {
        log("java_tool_input", Map.of("name", "get_stock", "sku", sku == null ? "" : sku));
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("商品编号不能为空");
        Integer stock = STOCK.get(sku);
        if (stock == null) throw new IllegalArgumentException("商品不存在：" + sku);
        Map<String, Object> result = Map.of("sku", sku, "stock", stock, "inStock", stock > 0);
        log("java_tool_output", Map.of("name", "get_stock", "result", result));
        return result;
    }

    public static Map<String, Object> dataset() {
        return Map.of("type", "simulated", "revision", "keyboards-v1",
                "catalog", CATALOG.stream().map(p -> Map.of("sku", p.sku(), "name", p.name(),
                        "priceYuan", p.priceYuan(), "features", p.features())).toList(), "stock", STOCK);
    }
    private void log(String type, Object data) { if (trace != null) trace.append(type, data, true); }
}
