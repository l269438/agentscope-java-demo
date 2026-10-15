# AgentScope Java Demo

AgentScope Java 系列的独立实验工程。JDK 17+、Maven、SDK 2.0.3，支持 DashScope 和本机 Ollama，默认保留 DashScope / qwen-plus。实战01—10已有对应实现；HTTP仅接入01电商商品筛选、库存查询与ReAct闭环，其他专题用独立入口。

HTTP使用JDK HttpServer，只监听`127.0.0.1`。HTTP接入、网页和日志保存由本项目提供，Agent执行使用AgentScope Java SDK；没有公网登录或部署能力。01电商前端通过SSE接收当次执行事件，已完成两个本机模型场景；09专题的EventView、SSE/OTel验证范围独立记录。

## 仓库范围

GitHub仓库：`l269438/agentscope-java-demo`，当前为私有仓库。

本仓库包含独立工程的01—10示例、模拟数据、测试、Postman集合和使用记录。`8088`上的整合演示及Harness基础补篇位于另一个工程，不包含在本仓库中。

`.env`、`runs/`、`target/`及IDE本机配置不提交。文档中的历史runId用于索引本机保留的运行原件；克隆仓库后，新执行产生的日志保存在自己的`runs/`目录。各篇已有的模型偏差和待完成项继续保留在测试记录中。

## 启动与检查

在本目录执行：

```sh
mvn -B -ntp test
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.LocalSmoke
mvn -B -ntp exec:exec
```

`test` 与 `LocalSmoke` 不调用模型，后者固定使用空凭据，并通过 HTTP 执行 SDK 工具检查。服务默认地址为 `http://127.0.0.1:18181`。IDE 可以直接运行 `DemoApplication.main`，工作目录设为本项目根目录。`exec:exec` 启动独立 JVM，避免 `exec:java` 的类加载器与 SDK JVM 退出钩子相互影响。

## 电商前端

配置模型并启动服务后，在浏览器打开 `http://127.0.0.1:18181/`。网页使用项目自带HTML、CSS和JavaScript，无需另装前端依赖。

1. 输入预算、用途和商品特点，或点“200元现货推荐”“100元无匹配”填入示例。
2. 点“发送”才会请求模型。左侧展示输入与模型回复，右侧“工具链”按发生顺序展示模型请求、工具参数、返回结果和结束状态；“参数与返回JSON”可展开。
3. “新查询”只清空当前页面；每次发送使用独立Agent，不承接上一轮对话，也不删除已有运行记录。

切换“事件日志”查看同次执行的业务事件、服务端序号和北京时间。两种视图共享当次SSE数据，切换不会重新请求模型；日志只包含模型请求开始、Java工具输入/输出、最终回复、失败及结束事件，序号可能因过滤而不连续。商品库作为可展开的辅助信息保留。

相邻`find_products`调用实际更改预算或特点时，工具链会显示参数差异；这个提示只描述调用参数，不推断模型思维或授权。当前模型提示词和三款商品数据未修改，“全部缺货后放宽预算到250元”的新场景尚未加入。

商品、价格和库存均为模拟数据。初始商品库不含库存值，页面在收到`get_stock`结果后更新库存；原始模型回复仅做文本排版。执行过程来自当次请求，没有读取旧日志回放。页面不提供锁库、下单、支付能力。

2026-10-02通过浏览器分别提交一次200元和100元场景：前者筛选两款、查询库存12/0后仅推荐149元KB100；后者获得空列表并说明无匹配。模型为本机Ollama/qwen3:4b-instruct，运行编号与截图见`docs/测试记录.md`。

本机模型消耗本机算力，切换云模型后每次“发送”可能产生费用。关闭页面不保证立即停止服务端任务，服务端沿用既有超时限制，网页不会自动重试。页面没有公网身份认证，只用于本机演示。

前端解析与状态判断的离线检查：

```sh
node --test tests/frontend-core.test.mjs
```

## 模型配置

### DashScope

在本机设置环境变量 `DASHSCOPE_API_KEY`，或复制 `.env.example` 为项目本地 `.env` 并填入。环境变量优先；`.env` 使用 `KEY=value` 格式，不支持 shell 展开。修改配置后重启服务。密钥不发送到聊天，不写入 Postman 请求或素材；`.env` 已忽略，不读取其他项目的配置。

DashScope 没有凭据时，健康检查与工具检查正常运行，模型入口返回 HTTP 503，记录 `blocked`、`modelCalls=0`，不生成模型回复。

### 本机 Ollama

先确认 Ollama 已启动、模型已下载，`ollama list` 可以查看。本次电商案例使用已安装的 `qwen3:4b-instruct`：

```sh
DEMO_PROVIDER=ollama DEMO_MODEL=qwen3:4b-instruct OLLAMA_BASE_URL=http://127.0.0.1:11434 mvn -B -ntp exec:exec
```

使用 SDK 2.0.3 的 `OllamaChatModel`，无需 DashScope 密钥；本工程只接受 `http://127.0.0.1:端口` 的 Ollama 地址。`DEMO_MODEL` 可指定已下载的其他模型，模型本身必须支持工具调用。当前 Ollama 上下文设为 8192 tokens。

服务启动后，可用同一份 Postman 模型请求文件，或显式执行两个本地模型案例：

```sh
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.LocalModelCases
```

该入口先读取 `/health`，仅允许 `provider=ollama`，依次提交 R01/R02，每例一次、不自动重跑。HTTP 原文另存于 `runs/model-cases/UUID.json`。普通 `test`、`LocalSmoke` 不执行这两个模型案例。

2026-10-02：qwen3:4b-instruct / stock-check-v2 完成 R01 筛选→两次库存查询→推荐现货，以及 R02 空结果处理；Postman 分别6/6、5/5通过。此前 qwen2.5:0.5b 在两版提示词下均跳过库存查询，失败原件保留。R02 有一次客户端残留请求体，修正 HTTP 完整 JSON 校验后单独复测，详细过程与 runId 见 `docs/测试记录.md`。这些结果仅覆盖固定模拟商品案例，DashScope / qwen-plus 尚未测试。

### 执行限制

云模型接口由提供商计费，本机 Ollama 使用本机算力。01 HTTP入口的SDK模型调用只尝试一次、最多8次主循环、每次输出最多1024 tokens、整条执行默认超时120秒，并限制同时执行一个模型任务。02—10取材入口另限制单案例最多16次请求，包含辅助摘要和失败请求开始次数；每轮120秒。v2.0.3的`maxRetries(1)`表示最多一次尝试，包含首次请求。这些条件无法保证云模型硬性费用上限，超时取消后提供商仍可能计费。

`/health` 的 `modelConfigurationReady=true` 仅表示配置条件满足，无法证明 Ollama 正在运行、模型已下载或模型能正确完成任务。

## HTTP 与 Postman

| 方法 | 地址 | 用途 |
| --- | --- | --- |
| GET | `/health` | 服务、SDK 版本、模型名称和凭据是否配置，不返回密钥 |
| GET | `/` | 电商选购页面，静态资源由同一服务提供 |
| GET | `/api/react/catalog` | 模拟商品基本信息；不提前返回库存 |
| GET | `/api/react/tools/schema` | 本地注册的两个工具及参数 Schema |
| POST | `/api/react/tools/run` | 手工提交参数，经 SDK 执行只读工具，不调用模型 |
| POST | `/api/react/run` | 提交用户消息，由 ReActAgent 选择并调用工具，返回回复与事件 |
| POST | `/api/react/chat` | 同样的模型任务，通过SSE发送运行编号、业务工具事件及最终回复 |

导入 `postman/tools-local.postman_collection.json` 可检查本地工具；`postman/react-model.postman_collection.json` 专供模型实测，两份文件分开，避免本地工具检查时误触发付费请求。`baseUrl` 默认设为 `http://127.0.0.1:18181`。

模型请求示例：

```json
{"message":"预算200元，想买适合办公室的静音键盘。只推荐有现货的商品，并说明价格、特点和库存。"}
```

工具检查示例：

```json
{"name":"find_products","arguments":{"maxPriceYuan":200,"feature":"静音"}}
```

工具入口的 HTTP 200 表示 SDK 执行请求完成；业务异常或参数校验失败会标记 `toolError=true`、记录 `tool_error`，错误内容保留在 `toolResult` 与 `resultText`。模型返回的 `stageSummary=true` 表示迭代上限总结，不能直接当作正常完成。

请求体必须是一个完整 JSON 对象；第二个对象或其他尾随内容返回 HTTP 400，在创建模型运行记录前拒绝。编辑已有 Postman 请求时，先全选替换完整请求体，并分别使用 R01/R02 的测试脚本，避免留下上一例的内容。

模型运行的 `status=completed` 表示 SDK 正常结束，业务要求是否达标还需核对工具轨迹与最终回答。provider、model 会写入响应及 metadata，Ollama 结果与 qwen-plus 结果分别记录。

## 运行记录

每个执行请求自动生成 `runs/日期/runId/`，不覆盖先前运行：

| 文件 | 内容 |
| --- | --- |
| `request.json` | 输入消息或手工工具参数 |
| `context.json` | 01 HTTP保存提示词、Schema和商品数据；LocalSeriesCases保存配置说明与资料路径，逐次实际模型输入和Schema在events.jsonl的model_input事件中 |
| `events.jsonl` | 带时间、顺序号和 runId 的应用可见 SDK 事件及工具执行记录 |
| `console.log` | 同次运行的文本日志；工具参数、结果与最终回复便于查看 |
| `workflow.log` | 执行时同步写入的关键事件日志，保留请求、模型调用序号、工具参数与完整结果、最终回复及结束状态；便于在 IDE 查看 |
| `response.json` | HTTP 返回结果或失败状态 |
| `metadata.json` | 版本、模式、限制、开始结束时间、结束状态与模型调用开始计数 |

`mode=local-tool-check`是人工工具检查；`mode=model-run`是01 HTTP模型入口，其`modelCalls`来自SDK的MODEL_CALL_START。`mode=local-series-model-run`是02—10取材入口，包装Model.stream订阅时计数并记录purpose=primary/summary，包含失败请求开始次数。两种计数均不能推算提供商最终成功次数或扣费；usage仅记录实际返回值，缺失时不填造。

`LocalSmoke` 另保存 HTTP 请求/响应原文与断言到 `runs/smoke/UUID.json`。JUnit 的临时记录不作为模型实测素材，测试统计保留在 `target/surefire-reports/`。

日志保留实际输入和可见输出，不记录密钥与模型思维片段；后续接入用户材料时应先确认保存范围，公开分享前检查敏感内容。截图采集IDE、终端、Postman或实际运行的前端界面，不将历史日志重排成HTML报告或卡片。

`workflow.log` 自 2026-10-02 的日志设施修订后生成，旧 runId 原件不补写。流式文本片段和其他事件仍保留在 `events.jsonl`、`console.log`；该关键事件日志没有增加或替换工具执行结果。

## 02—10本机模型取材现态

2026-10-02使用既有Ollama/qwen3:4b-instruct、8192上下文、1024输出上限、temperature0。`LocalSeriesCases`每次显式执行一个案例，新建UUID记录；没有批量重试、子Agent、云请求、HTTP服务重启或发布。

```sh
DEMO_PROVIDER=ollama DEMO_MODEL=qwen3:4b-instruct mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.LocalSeriesCases '-Dexec.args=-classpath %classpath dev.yiyan.agentscope.LocalSeriesCases 03'
```

本机Maven不在PATH时使用IDE自带的`/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`。04/05续接给本版runId，10给已准备镜像ID；当前电商记录见`docs/电商模型实测-2026-10-02.md`和源码，不自动下载镜像。

当前01—10统一为电商键盘选购。02—10新增11个ecommerce-v1 UUID、29次模型请求开始，10 completed/1 failed；03/05/07/09预算解释有误，06未正确分页，10命令被白名单拦截，均保留原输出。08只做主计划ASK，未批准、子Agent0。08:34:19无模型回归130项通过，另以固定响应完成商品CSV的Docker预览/恢复/隔离；不据此宣称所有模型案例成功。详见docs/电商模型实测-2026-10-02.md；此前20次会议取材原件保留，旧索引已标历史。

## 实战 02：权限与人工确认

permission包使用product_info查询模拟商品，save_shortlist保存本进程选购清单；查询ALLOW、保存ASK、维护DENY。身份由RuntimeContext传入，原ConfirmResult、5分钟票据、用户/会话和单次许可绑定；保存时仍校验商品、库存和备注。电商批准运行确认前0条、批准后1条，不锁库、不下单、不付款。10项离线检查覆盖拒绝、维护、过期、重复及业务校验；新版拒绝/维护模型本轮未运行。

## 实战 03：工作区

`workspace/WorkspaceDemo`装配Harness与fixtures/workspace-03商品目录、库存快照、选购规则；只保留四个读取/搜索工具，shell及辅助记忆调用关闭。新模型读取三文件并推荐KB100，但KB200预算理由矛盾，完整推荐解释未达标。

```sh
mvn -B -ntp test
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.workspace.InspectWorkspace
```

InspectWorkspace 人工指定工具参数，不请求模型。显式运行 WorkspaceDemo 才会按项目模型配置提交任务，云服务按提供商规则计费。该专题尚未接入 HTTP，不改变现有电商服务；正文及资料说明位于文章仓库实战03目录。

## 实战 04：会话状态

session/SessionStateDemo使用JsonFileAgentStateStore保存预算、偏好与候选快照，后端身份demo-user；参数为sessionId和消息。显式运行才请求配置模型，同ID/目录续接会话。电商seed与新JVM恢复各留新UUID，恢复后正确回答200元/KB100/149元/12件；旧库存不代表当前可售。

```sh
mvn -B -ntp test
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.session.SessionStateCheck
```

本地检查在独立UUID目录中启动两个JVM，保存/恢复人工状态，禁止外部模型请求。JUnit固定响应仅验证框架行为。模型入口的target/session-state-04会受开发清理影响，正式服务应换独立持久目录，并认证身份、校验会话归属；未接入HTTP。

## 实战 05：文件记忆

memory/FileMemoryDemo在用户独立工作区保留三种记忆工具，关闭自动辅助调用；FileMemoryCheck手工保存选购偏好，再新建Agent/会话搜索，不请求外部模型。

```sh
mvn -B -ntp test
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.memory.FileMemoryCheck
```

模型入口IDE参数为sessionId和消息，显式运行才请求提供商；target/file-memory-05/demo-user会受开发清理影响。直接Toolkit入口绕过ReAct权限引擎，需调用方授权；追加不保证去重或跨文件事务。05-save-v3安装本项目ApprovedMemoryTool，绑定身份/会话、批准内容与单次许可，SDK默认工具没有此限制。电商版只保存批准偏好；新会话系统输入已注入memory_context，但搜索未命中、memory_get只读空行，来源行号和预算解释有误，不能称工具完整检索通过。hooks关闭，未接HTTP。

## 实战 06：上下文与长结果

context包保留摘要、最近消息与长商品报告原件；ContextCheck以固定响应检查框架。电商06-guided实际卸载全文并调用摘要一次，但模型把字面词“选购结论”改为整句，未命中后没有分页，错误声称全文没有KB100；原文件第162行保留149元/12件。组件链路与模型业务评价分开，旧会议结果不作新电商成功证明，没有Token节省结论。

## 实战 07：Skills 与工具组

skill/SkillDemo安装fixtures/skills-07的product-selection技能、选购模板与默认关闭的product_tools组，不覆盖已有资料；完整模型入口显式运行才请求配置提供商。

```sh
mvn -B -ntp test
mvn -B -ntp exec:exec -Dexec.mainClass=dev.yiyan.agentscope.skill.SkillCheck
```

SkillCheck以固定响应验证加载、资源、组激活与商品返回，无外部模型请求。2.0.3需在运行时Toolkit重注册META工具。电商07实际加载SKILL.md、激活product_tools、读取product_report；未读参考模板，199元预算解释有误，完整技能步骤与业务解释未达标。未下单、发消息或启动子Agent，详见文章07目录。

## 实战 08：子任务声明与计划确认

subagent包声明product-finder商品筛选/stock-reviewer库存复核，主Agent启用计划、plan_exit显式ASK、原对象票据和强制同步配置；默认仅检查、不委派：

```sh
mvn -B -ntp test exec:exec -Dexec.mainClass=dev.yiyan.agentscope.subagent.SubagentCheck
```

12项配置/主Agent计划检查通过，默认外部模型0/子Agent0。电商08-plan模型写PLAN.md并进入plan_exit ASK，未先读product_report；运行时移除委派工具，未批准恢复、子Agent0。完整协作需用户明确授权；主计划不能证明分工效果，同步/后台与传播按同版源码说明。

## 实战 09：事件流与Middleware

observe包提供单请求事件视图、执行前授权、白名单日志和FinalAnswerFilter。默认ObserveDemo仅运行固定响应检查：

```sh
mvn -B -ntp test exec:exec -Dexec.mainClass=dev.yiyan.agentscope.observe.ObserveDemo
```

10项专用检查通过；默认外部模型0/子Agent0。电商09单订阅、2请求/1次product_report读取，EventView关联success、5条白名单日志结束；KB100/149/12正确，KB200/199元预算解释有误，原输出保留。2.0.3两阶段replyId可不同，单请求按source/callId关联；文本按replyId/blockId分区。09未实现SSE网页或追踪平台，AgentEnd/span OK不能当业务成功。

## 实战 10：Docker沙箱与工作区恢复

sandbox包及fixtures/sandbox-10提供products.csv、固定Python选购预览脚本、限定投影、参数白名单、LocalSnapshotSpec与SESSION配置。

```sh
mvn -B -ntp test exec:exec -Dexec.mainClass=dev.yiyan.agentscope.sandbox.SandboxDemo
```

默认纯配置检查，容器/外部模型/子Agent0，10项专用检查通过。08:34:19显式SandboxDockerCheck使用既有镜像和固定响应驱动3次调用，商品CSV预览、同会话恢复、独立会话缺文件通过，临时容器余0。电商10模型提交白名单外execute参数，被SandboxGuard在执行前拦截，SDK failed、无最终推荐；原件保留，容器已清理，未放宽边界或自动重跑。原有容器不动。SESSION键只有sessionId，需后端唯一ID及归属检查；快照不保存完整容器根，状态store当前进程内。

## 数据与维护

商品、价格、库存均为模拟数据，沿用01三款键盘；商品/库存只读，02仅确认后写本进程模拟清单。不连接商城、不锁库、不下单、不支付、不发货、不涉及售后，库存快照不保证之后仍有现货。历史会议资料已移至legacy/meeting-examples-2026-10-02，不参与当前入口。

`react/` 保存专题实现，`runtime/` 保存配置、HTTP 和运行记录，`docs/案例清单.md` 记录预先确定的条件，`docs/测试记录.md` 记录已做与待做项。后续专题计划见 `docs/系列实验规划.md`。
