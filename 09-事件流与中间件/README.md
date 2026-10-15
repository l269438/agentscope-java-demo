# 09 事件流与中间件

消费 SDK 事件，通过 Middleware 校验工具执行、记录白名单日志并提取最终回复。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/observe/)：`ObserveDemo.java、ObserveMiddleware.java、EventView.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/observe/ObserveCheck.java)。
- 测试：[`ObserveDemoTest.java`](src/test/java/dev/yiyan/agentscope/ObserveDemoTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '09-事件流与中间件/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

检查同次请求的事件关联、文本过滤和工具日志；本章不包含观测平台。

## 单篇测试

```sh
mvn -B -ntp -pl '09-事件流与中间件' -am test
```

本篇复用[07 Skills与工具组](../07-Skills与工具组/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.observe.ObserveDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

模型入口需要显式`--run`。本篇只消费独立事件流，01的网页SSE另行维护。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
