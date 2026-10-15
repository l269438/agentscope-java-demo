# 07 Skills与工具组

加载商品选购 Skill、读取参考模板，并按需激活商品查询工具组。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/skill/)：`SkillDemo.java、SkillCheck.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/skill/SkillCheck.java)。
- 测试：[`SkillDemoTest.java`](src/test/java/dev/yiyan/agentscope/SkillDemoTest.java)。
- [工作区与模拟资料](fixtures/skills-07/)。

## 运行

在仓库根目录执行：

```sh
sh '07-Skills与工具组/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

检查 Skill 发现、资源读取、工具组激活与模拟商品返回。

## 单篇测试

```sh
mvn -B -ntp -pl '07-Skills与工具组' -am test
```

本篇复用[04 会话状态与断点恢复](../04-会话状态与断点恢复/)中的组件；`-am`一并构建所需章节。

## 使用模型

模型入口为`dev.yiyan.agentscope.skill.SkillDemo`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
