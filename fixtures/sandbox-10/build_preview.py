import csv
from pathlib import Path

with Path("products.csv").open(encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))
lines = ["# 模拟商品选购预览", "", "预算200元，静音办公；库存为模拟快照。", "", "| 商品 | 价格（元） | 库存快照 | 结论 |", "| --- | --- | --- | --- |"]
for row in rows:
    price, stock = int(row["price_yuan"]), int(row["stock"])
    reason = "超预算" if price > 200 else "不符合静音要求" if "静音" not in row["features"].split("/") else "无库存" if stock <= 0 else "推荐"
    lines.append(f"| {row['sku']} {row['name']} | {price} | {stock} | {reason} |")
lines.extend(["", "只生成选购预览，不锁库存、不下单、不付款。"])
Path("selection-preview.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("preview=selection-preview.md; rows=" + str(len(rows)))
