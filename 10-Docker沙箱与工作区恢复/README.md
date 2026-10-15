# 10 Docker沙箱与工作区恢复

为商品 CSV 预览选择隔离执行环境，配置命令白名单和工作区快照。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/sandbox/)：`SandboxDemo.java、SandboxGuard.java、SandboxDockerCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/sandbox/SandboxCheck.java)。
- 测试：[`SandboxDemoTest.java`](src/test/java/dev/yiyan/agentscope/SandboxDemoTest.java)。
- [工作区与模拟资料](fixtures/sandbox-10/)。

## 运行

在仓库根目录执行：

```sh
sh '10-Docker沙箱与工作区恢复/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

默认只检查沙箱配置与权限，不创建容器或下载镜像。

## 单篇测试

```sh
mvn -B -ntp -pl '10-Docker沙箱与工作区恢复' -am test
```

本篇复用[04 会话状态与断点恢复](../04-会话状态与断点恢复/)、[07 Skills与工具组](../07-Skills与工具组/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.sandbox.SandboxDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

`SandboxDockerCheck`需要已准备镜像的完整sha256；模型入口需要显式`--run`与镜像参数。默认脚本不创建容器、不拉取镜像。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
