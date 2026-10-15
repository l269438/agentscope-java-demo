# 04 会话状态与断点恢复

把会话运行状态保存到文件，再用相同用户和会话标识恢复。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/session/)：`SessionStateDemo.java、SessionStateCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/session/SessionStateCheck.java)。
- 测试：[`SessionStateDemoTest.java`](src/test/java/dev/yiyan/agentscope/SessionStateDemoTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '04-会话状态与断点恢复/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

启动两个本地 JVM，验证状态写入与跨进程恢复；不请求模型。

## 单篇测试

```sh
mvn -B -ntp -pl '04-会话状态与断点恢复' -am test
```


## 使用模型

模型入口为`dev.yiyan.agentscope.session.SessionStateDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

模型入口需要会话ID与消息两个参数；持久文件仍位于根目录`target/`，开发清理会影响这些文件。接入服务时应使用独立持久目录并校验会话归属。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
