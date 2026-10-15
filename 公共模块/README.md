# 公共模块

各篇共用的模型配置、模型构建、运行日志、SSE协议和固定响应模型。

- `runtime/DemoConfig.java`：读取仓库根目录配置。
- `runtime/DemoModels.java`：创建 DashScope / Ollama 模型。
- `runtime/RunTrace.java`：保存运行编号、事件与结果。
- `runtime/FrontendEventStream.java`：SSE事件编码。
- `support/OfflineModel.java`：离线检查的固定响应模型。
- `support/RecordingFixtureModel.java`：固定回复并记录输入，供会话、记忆与上下文测试复用。

本模块没有业务启动入口。全量检查在仓库根目录执行`mvn -B -ntp test`。
