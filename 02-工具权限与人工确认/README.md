# 02 工具权限与人工确认

查询工具直接放行，保存模拟选购清单前暂停，收到批准后继续执行。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/permission/)：`PermissionDemo.java、ToolPolicy.java、ConfirmationGate.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/permission/PermissionCheck.java)。
- 测试：[`PermissionDemoTest.java`](src/test/java/dev/yiyan/agentscope/PermissionDemoTest.java)。

## 运行

在仓库根目录执行：

```sh
sh '02-工具权限与人工确认/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

检查 ALLOW、ASK、DENY 三条路径，以及批准前后模拟选购清单的变化。

## 单篇测试

```sh
mvn -B -ntp -pl '02-工具权限与人工确认' -am test
```

本篇复用[01 ReAct与工具调用](../01-ReAct与工具调用/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.permission.PermissionDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
