---
name: product-selection
description: 按预算、特点和库存快照生成带来源的商品选购建议。
---

# 商品选购

1. 读取 references/selection-template.md，确认输出字段。
2. 调用 reset_equipped_tools，to_activate 传完整工具组名单 ["product_tools"]。
3. 调用 product_report 获取模拟商品与库存快照。
4. 按预算和必需特点筛选，仅推荐库存大于0的商品；逐项说明排除原因。
5. 输出资料来源；只返回选购建议，不锁库、不下单、不付款。
