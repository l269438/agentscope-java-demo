# 08 子Agent与计划确认

声明商品筛选与库存复核子 Agent，在主计划阶段加入人工确认。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/subagent/)：`SubagentDemo.java、PlanApprovalGate.java、SubagentCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/subagent/SubagentCheck.java)。
- 测试：[`SubagentDemoTest.java`](src/test/java/dev/yiyan/agentscope/SubagentDemoTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '08-子Agent与计划确认/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

默认只检查子 Agent 声明、计划权限与确认绑定，不运行完整委派任务。

## 单篇测试

```sh
mvn -B -ntp -pl '08-子Agent与计划确认' -am test
```

本篇复用[07 Skills与工具组](../07-Skills与工具组/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.subagent.SubagentDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

本独立工程的完整子Agent协作仍待完成；模型计划需要显式`--run`，默认脚本只检查配置。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
