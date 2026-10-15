# 05 文件记忆与跨会话复用

保存用户允许记录的选购偏好，在新会话中读取或搜索文件记忆。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/memory/)：`FileMemoryDemo.java、FileMemoryCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/memory/FileMemoryCheck.java)。
- 测试：[`FileMemoryDemoTest.java`](src/test/java/dev/yiyan/agentscope/FileMemoryDemoTest.java)、[`ApprovedMemoryToolTest.java`](src/test/java/dev/yiyan/agentscope/ApprovedMemoryToolTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '05-文件记忆与跨会话复用/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

检查文件写入、身份与会话隔离，以及批准内容和单次许可的绑定。

## 单篇测试

```sh
mvn -B -ntp -pl '05-文件记忆与跨会话复用' -am test
```

本篇复用[04 会话状态与断点恢复](../04-会话状态与断点恢复/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.memory.FileMemoryDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

模型入口需要会话ID与消息两个参数；持久文件仍位于根目录`target/`，开发清理会影响这些文件。接入服务时应使用独立持久目录并校验会话归属。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
