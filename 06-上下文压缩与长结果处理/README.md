# 06 上下文压缩与长结果处理

将长工具结果卸载到文件，管理历史摘要与近期消息。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/context/)：`ContextDemo.java、ContextCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/context/ContextCheck.java)。
- 测试：[`ContextDemoTest.java`](src/test/java/dev/yiyan/agentscope/ContextDemoTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '06-上下文压缩与长结果处理/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

用固定响应检查长结果卸载、摘要与历史状态保留。

## 单篇测试

```sh
mvn -B -ntp -pl '06-上下文压缩与长结果处理' -am test
```

本篇复用[04 会话状态与断点恢复](../04-会话状态与断点恢复/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.context.ContextDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
