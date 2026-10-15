#!/usr/bin/env sh
set -eu
demo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
chapter=${1:-}
if [ "$#" -gt 0 ]; then shift; fi
mode=${1:---check}
if [ "$#" -gt 0 ]; then shift; fi
if [ "$#" -ne 0 ]; then
    printf '%s\n' '参数过多；默认离线检查，01另支持 --web。' >&2
    exit 2
fi
case "$chapter" in
    01) module='01-ReAct与工具调用' ;;
    02) module='02-工具权限与人工确认' ;;
    03) module='03-工作区与规则加载' ;;
    04) module='04-会话状态与断点恢复' ;;
    05) module='05-文件记忆与跨会话复用' ;;
    06) module='06-上下文压缩与长结果处理' ;;
    07) module='07-Skills与工具组' ;;
    08) module='08-子Agent与计划确认' ;;
    09) module='09-事件流与中间件' ;;
    10) module='10-Docker沙箱与工作区恢复' ;;
    *) printf '%s\n' '请通过01—10目录中的 run.sh 运行。' >&2; exit 2 ;;
esac
main_override=
case "$mode" in
    --check) ;;
    --web)
        if [ "$chapter" != 01 ]; then
            printf '%s\n' '--web 仅用于01的本地选购网页。' >&2
            exit 2
        fi
        main_override=-Dexec.mainClass=dev.yiyan.agentscope.DemoApplication
        ;;
    --help|-h)
        printf '%s\n' '用法：sh run.sh [--check|--web]' '默认 --check，不请求模型；--web 仅限01，发送消息后请求配置模型。'
        exit 0
        ;;
    *) printf '%s\n' '不支持的参数；使用 --help 查看说明。' >&2; exit 2 ;;
esac
maven_cmd=${MAVEN_CMD:-mvn}
if ! command -v "$maven_cmd" >/dev/null 2>&1; then
    printf '%s\n' '找不到Maven，请将其加入PATH，或设置 MAVEN_CMD 为mvn的完整路径。' >&2
    exit 127
fi
cd "$demo_root"
"$maven_cmd" -q -ntp -pl "$module" -am -DskipTests install
if [ -n "$main_override" ]; then
    exec "$maven_cmd" -q -ntp -pl "$module" exec:exec "$main_override"
fi
exec "$maven_cmd" -q -ntp -pl "$module" exec:exec
