# 03 工作区与规则加载

用 HarnessAgent 加载工作区规则、商品资料和库存快照，限制可用文件工具。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/workspace/)：`WorkspaceDemo.java、InspectWorkspace.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/workspace/InspectWorkspace.java)。
- 测试：[`WorkspaceDemoTest.java`](src/test/java/dev/yiyan/agentscope/WorkspaceDemoTest.java)。
- [工作区与模拟资料](fixtures/workspace-03/)。

## 运行

在仓库根目录执行：

```sh
sh '03-工作区与规则加载/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

检查工作区 AGENTS.md、知识目录以及只读文件工具的访问边界。

## 单篇测试

```sh
mvn -B -ntp -pl '03-工作区与规则加载' -am test
```


## 使用模型

模型入口为`dev.yiyan.agentscope.workspace.WorkspaceDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
