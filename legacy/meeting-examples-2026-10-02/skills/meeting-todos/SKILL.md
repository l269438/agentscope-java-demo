---
name: meeting-todos
description: 将会议纪要整理为带来源的待办清单。
---

# 会议待办

1. 读取 references/todo-template.md，确认输出字段。
2. 调用 reset_equipped_tools，to_activate 传完整工具组名单 ["meeting_tools"]。
3. 调用 meeting_report 获取模拟纪要。
4. 按模板列出明确任务；没有负责人或日期的字段写“待确认”，不要推测。
5. 输出资料来源；只返回清单，不创建业务任务。
