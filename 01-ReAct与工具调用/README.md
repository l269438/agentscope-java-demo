# 01 ReAct与工具调用

用商品筛选与库存查询串起 ReAct 工具调用闭环，网页展示同次请求的工具链和事件。

## 看源码

- [专题源码](src/main/java/dev/yiyan/agentscope/react/)：`react/ReactRunner.java、react/ProductQueryTools.java`。
- [离线运行入口](src/main/java/dev/yiyan/agentscope/LocalSmoke.java)。
- 测试：[`ProductQueryToolsTest.java`](src/test/java/dev/yiyan/agentscope/ProductQueryToolsTest.java)、[`DemoRuntimeTest.java`](src/test/java/dev/yiyan/agentscope/DemoRuntimeTest.java)、[`FrontendTest.java`](src/test/java/dev/yiyan/agentscope/FrontendTest.java)。
- [网页](src/main/resources/web/)、[Postman集合](postman/)、[前端测试](tests/frontend-core.test.mjs)。

## 运行

在仓库根目录执行：

```sh
sh '01-ReAct与工具调用/run.sh'
```

也可进入本目录执行`sh run.sh`。脚本先构建本篇及其依赖，再执行离线检查；需要JDK17+和Maven。Maven未加入PATH时，用`MAVEN_CMD="/实际路径/mvn"`指定可执行文件。

筛选预算内静音键盘，查询 KB100、KB200 库存；只读工具检查覆盖现货与无匹配情况。

## 单篇测试

```sh
mvn -B -ntp -pl '01-ReAct与工具调用' -am test
```


## 使用模型

模型入口为`dev.yiyan.agentscope.DemoApplication`，从IDE运行时将工作目录设为仓库根目录。配置见[根目录说明](../README.md#模型配置)，云模型可能计费。上面的默认脚本不请求外部模型。

启动选购网页：

```sh
DEMO_PROVIDER=ollama DEMO_MODEL=qwen3:4b-instruct sh '01-ReAct与工具调用/run.sh' --web
```

打开`http://127.0.0.1:18181/`，点击发送后才请求模型。商品、库存为模拟数据，没有下单或支付功能。

商品、价格和库存均为模拟数据。模型运行与偏差见[测试记录](../docs/测试记录.md)、[案例清单](../docs/案例清单.md)。
