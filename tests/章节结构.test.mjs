import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync, readdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const chapters = [
  {
    "dir": "01-ReAct与工具调用",
    "pkg": "react",
    "tests": [
      "ProductQueryToolsTest",
      "DemoRuntimeTest",
      "FrontendTest"
    ],
    "check": "dev.yiyan.agentscope.LocalSmoke"
  },
  {
    "dir": "02-工具权限与人工确认",
    "pkg": "permission",
    "tests": [
      "PermissionDemoTest"
    ],
    "check": "dev.yiyan.agentscope.permission.PermissionCheck"
  },
  {
    "dir": "03-工作区与规则加载",
    "pkg": "workspace",
    "tests": [
      "WorkspaceDemoTest"
    ],
    "check": "dev.yiyan.agentscope.workspace.InspectWorkspace",
    "fixture": "workspace-03"
  },
  {
    "dir": "04-会话状态与断点恢复",
    "pkg": "session",
    "tests": [
      "SessionStateDemoTest"
    ],
    "check": "dev.yiyan.agentscope.session.SessionStateCheck"
  },
  {
    "dir": "05-文件记忆与跨会话复用",
    "pkg": "memory",
    "tests": [
      "FileMemoryDemoTest",
      "ApprovedMemoryToolTest"
    ],
    "check": "dev.yiyan.agentscope.memory.FileMemoryCheck"
  },
  {
    "dir": "06-上下文压缩与长结果处理",
    "pkg": "context",
    "tests": [
      "ContextDemoTest"
    ],
    "check": "dev.yiyan.agentscope.context.ContextCheck"
  },
  {
    "dir": "07-Skills与工具组",
    "pkg": "skill",
    "tests": [
      "SkillDemoTest"
    ],
    "check": "dev.yiyan.agentscope.skill.SkillCheck",
    "fixture": "skills-07"
  },
  {
    "dir": "08-子Agent与计划确认",
    "pkg": "subagent",
    "tests": [
      "SubagentDemoTest"
    ],
    "check": "dev.yiyan.agentscope.subagent.SubagentCheck"
  },
  {
    "dir": "09-事件流与中间件",
    "pkg": "observe",
    "tests": [
      "ObserveDemoTest"
    ],
    "check": "dev.yiyan.agentscope.observe.ObserveCheck"
  },
  {
    "dir": "10-Docker沙箱与工作区恢复",
    "pkg": "sandbox",
    "tests": [
      "SandboxDemoTest"
    ],
    "check": "dev.yiyan.agentscope.sandbox.SandboxCheck",
    "fixture": "sandbox-10"
  }
];

const read = file => readFileSync(path.join(root, file), 'utf8');
for (const c of chapters) {
  test(c.dir + '具备源码、测试、中文说明和运行入口', () => {
    for (const file of ['pom.xml', 'README.md', 'run.sh', 'src/main/java/dev/yiyan/agentscope/' + c.pkg]) {
      assert.ok(existsSync(path.join(root, c.dir, file)), c.dir + '/' + file);
    }
    for (const name of c.tests) assert.ok(existsSync(path.join(root, c.dir, 'src/test/java/dev/yiyan/agentscope', name + '.java')));
    assert.ok(read(c.dir + '/pom.xml').includes('<exec.mainClass>' + c.check + '</exec.mainClass>'));
    assert.ok(read('pom.xml').includes('<module>' + c.dir + '</module>'));
    assert.ok(read('README.md').includes('](' + c.dir + '/)'));
    if (c.fixture) assert.ok(existsSync(path.join(root, c.dir, 'fixtures', c.fixture)));
  });
}
test('公共模块与跨篇运行器分离', () => {
  assert.ok(existsSync(path.join(root, '公共模块/src/main/java/dev/yiyan/agentscope/runtime/DemoConfig.java')));
  assert.ok(existsSync(path.join(root, '系列运行器/src/main/java/dev/yiyan/agentscope/LocalSeriesCases.java')));
  assert.ok(existsSync(path.join(root, '.mvn/jvm.config')));
});
test('默认入口与网页模式有显式边界', () => {
  const script = read('scripts/运行示例.sh');
  assert.ok(script.includes('---check'));
  assert.ok(script.includes('chapter" != 01'));
  assert.ok(!script.includes('LocalSeriesCases'));
  assert.ok(!script.includes('docker run'));
});
test('根目录不维护平行的旧源码副本', () => {
  for (const dir of ['src/main/java', 'src/test/java']) {
    assert.ok(!existsSync(path.join(root, dir)) || !hasJava(path.join(root, dir)));
  }
});
test('章节README的本地链接均存在', () => {
  for (const c of chapters) {
    const content = read(c.dir + '/README.md');
    for (const match of content.matchAll(/\]\(([^)]+)\)/g)) {
      if (/^https?:/.test(match[1])) continue;
      const target = match[1].split('#')[0];
      assert.ok(existsSync(path.resolve(root, c.dir, target)), c.dir + ' -> ' + target);
    }
  }
});
function hasJava(folder) {
  return readdirSync(folder, {withFileTypes: true}).some(entry => entry.isDirectory()
    ? hasJava(path.join(folder, entry.name)) : entry.name.endsWith('.java'));
}
