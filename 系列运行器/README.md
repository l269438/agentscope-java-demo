# 系列运行器

`LocalSeriesCases`统一记录02—10的单案例模型请求与结果，依赖编号章节中的实现。

- [取材源码](src/main/java/dev/yiyan/agentscope/LocalSeriesCases.java)
- [边界测试](src/test/java/dev/yiyan/agentscope/LocalSeriesCasesTest.java)
- [既有模型记录](../docs/模型实测续作-2026-10-02.md)

在根目录执行`mvn -B -ntp install`后，可从IDE运行该类，工作目录设为仓库根目录。必须显式选择一个受支持案例；续接案例需要前次runId，容器案例需要已准备的镜像ID。具体参数由源码校验，不支持自动批量任务或自动重试。

各篇默认`run.sh`不调用此入口。旧运行原件保留本机，不随Git提交。
