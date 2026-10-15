package dev.yiyan.agentscope.permission;

import dev.yiyan.agentscope.react.ProductQueryTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 选购清单为进程内模拟记录，保存清单不扣库存、不创建订单。 */
public final class SelectionTools {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> USERS = Set.of("alice", "bob");
    private final ConcurrentHashMap<String, Map<String, Object>> selections = new ConcurrentHashMap<>();
    private final ProductQueryTools products = new ProductQueryTools();

    public int selectionCount() { return selections.size(); }

    @Tool(name = "product_info", description = "查询模拟商品的名称、价格、特点和库存，不下单",
            readOnly = true, concurrencySafe = true)
    public Map<String, Object> productInfo(
            @ToolParam(name = "sku", description = "商品编号，例如KB100") String sku,
            RuntimeContext context) {
        requireUser(context);
        return snapshot(sku);
    }

    @Tool(name = "save_shortlist", description = "将现货商品保存到当前用户的模拟选购清单，不锁库存、不下单、不付款",
            readOnly = false, concurrencySafe = true)
    public Map<String, Object> saveShortlist(
            @ToolParam(name = "sku", description = "已查询的商品编号") String sku,
            @ToolParam(name = "note", description = "选购备注，1至100个字符") String note,
            RuntimeContext context) {
        String user = requireUser(context);
        Map<String, Object> product = snapshot(sku);
        if (!Boolean.TRUE.equals(product.get("inStock"))) throw new IllegalArgumentException("商品暂无库存");
        if (note == null || note.isBlank() || note.length() > 100) throw new IllegalArgumentException("选购备注须为1至100个字符");
        Map<String, Object> entry = Map.of("status", "模拟选购清单已保存", "sku", sku,
                "note", note.strip(), "userId", user, "orderCreated", false);
        if (selections.putIfAbsent(user + ":" + sku, entry) != null) throw new IllegalStateException("该商品已在选购清单中");
        return entry;
    }

    private Map<String, Object> snapshot(String sku) {
        Map<String, Object> stock = products.getStock(sku);
        @SuppressWarnings("unchecked")
        var catalog = (java.util.List<Map<String, Object>>) ProductQueryTools.dataset().get("catalog");
        var data = catalog.stream().filter(p -> sku.equals(p.get("sku"))).findFirst().orElseThrow();
        var result = new java.util.LinkedHashMap<>(data);
        result.putAll(stock);
        return Map.copyOf(result);
    }

    private String requireUser(RuntimeContext context) {
        if (context == null || context.getUserId() == null || !USERS.contains(context.getUserId())) throw new SecurityException("无权访问选购清单");
        return context.getUserId();
    }
}
