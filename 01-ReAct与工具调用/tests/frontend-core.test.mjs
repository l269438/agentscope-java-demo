import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {SseFrames, resultMatches, replyComplete, EXAMPLES, traceVisible, traceTime, traceSequence, traceLog, queryChange} from '../src/main/resources/web/core.mjs';

test('SSE解析任意UTF8分片与换行', () => {
  const text = 'event: run\r\ndata: {"runId":"fixture"}\r\n\r\nevent: trace\ndata: {"text":"静音键盘\\n库存12"}\n\n';
  const bytes = new TextEncoder().encode(text);
  for (const width of [1, 2, 3, 11, 64, bytes.length]) {
    const observed = [];
    const frames = new SseFrames((name, data) => observed.push([name, data]));
    const decoder = new TextDecoder();
    for (let i = 0; i < bytes.length; i += width) frames.push(decoder.decode(bytes.slice(i, i + width), {stream: true}));
    frames.push(decoder.decode()); frames.finish();
    assert.deepEqual(observed, [['run', {runId: 'fixture'}], ['trace', {text: '静音键盘\n库存12'}]]);
  }
});
test('不接受未完整结束的帧', () => {
  const frames = new SseFrames(() => {});
  frames.push('event: done\ndata: {"ok":true}');
  assert.throws(() => frames.finish(), /未完整结束/);
});
test('允许注释与多行JSON数据帧', () => {
  const values = [];
  const frames = new SseFrames((name, data) => values.push([name, data]));
  frames.push(': heartbeat\n\nevent: done\ndata: {\ndata: "ok": true}\n\n'); frames.finish();
  assert.deepEqual(values, [['done', {ok: true}]]);
});
test('库存返回匹配对应sku，已完成调用不会再次匹配', () => {
  const step = {name: 'get_stock', input: {sku: 'KB100'}, result: null};
  assert.equal(resultMatches(step, {name: 'get_stock', result: {sku: 'KB200', stock: 0}}), false);
  assert.equal(resultMatches(step, {name: 'get_stock', result: {sku: 'KB100', stock: 12}}), true);
  step.result = {sku: 'KB100', stock: 12};
  assert.equal(resultMatches(step, {name: 'get_stock', result: {sku: 'KB100'}}), false);
  assert.equal(resultMatches({name: 'find_products', input: {}, result: []}, {name: 'find_products', result: []}), false);
});
test('迭代总结与空回复不标记正常完成', () => {
  assert.equal(replyComplete({reply: {text: '有结果', stageSummary: false, generateReason: 'MODEL_STOP'}}), true);
  assert.equal(replyComplete({reply: {text: '阶段总结', stageSummary: true, generateReason: 'MAX_ITERATIONS'}}), false);
  assert.equal(replyComplete({reply: {text: ' ', stageSummary: false, generateReason: 'MODEL_STOP'}}), false);
  assert.equal(replyComplete({}), false);
});
test('示例只有输入；页面脚本不执行模型HTML、不读取历史运行文件', () => {
  assert.match(EXAMPLES.stock, /200元/); assert.match(EXAMPLES.empty, /100元/);
  const source = readFileSync(new URL('../src/main/resources/web/app.js', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML|eval\(|fetch\(['"]\/runs\//);
  assert.match(source, /fetch\('\/api\/react\/chat'/);
  assert.match(source, /document\.createTextNode/);
});

test('日志取服务端序号与北京时间；保留序号间隙和原参数', () => {
  const event = {sequence: 27, time: '2026-10-02T00:02:03.456Z', type: 'java_tool_input',
    payload: {name: 'find_products', maxPriceYuan: 200, feature: '静音'}};
  assert.equal(traceSequence(event), '#0027');
  assert.equal(traceTime(event), '08:02:03.456');
  assert.equal(traceLog(event), '#0027 08:02:03.456 [java_tool_input]\n{\n  "name": "find_products",\n  "maxPriceYuan": 200,\n  "feature": "静音"\n}\n\n');
  assert.equal(event.sequence, 27);
});

test('日志白名单不输出思维、普通文本片段或未知事件', () => {
  for (const type of ['THINKING_DELTA', 'TEXT_DELTA', 'request', 'unknown']) {
    assert.equal(traceVisible({type}), false);
    assert.equal(traceLog({type, payload: 'private'}), '');
  }
  for (const type of ['MODEL_CALL_START', 'java_tool_input', 'java_tool_output', 'final_reply', 'failure', 'run_end']) {
    assert.equal(traceVisible({type}), true);
  }
});

test('未提供时间和序号时不伪造当前时间或编号', () => {
  assert.equal(traceTime({}), '—');
  assert.equal(traceTime({time: 'invalid'}), '—');
  assert.equal(traceSequence({}), '序号未提供');
});

test('条件变化仅对实际相邻筛选参数做差异比较', () => {
  const first = {name: 'find_products', maxPriceYuan: 200, feature: '静音'};
  assert.equal(queryChange(null, first), '');
  assert.equal(queryChange(first, {...first}), '');
  assert.equal(queryChange(first, {...first, maxPriceYuan: 250}), '查询条件变化：预算 200 → 250 元');
  assert.equal(queryChange(first, {...first, feature: '无线'}), '查询条件变化：特点 静音 → 无线');
  assert.equal(queryChange(first, {...first, maxPriceYuan: 150, feature: '无线'}), '查询条件变化：预算 200 → 150 元；特点 静音 → 无线');
  assert.equal(queryChange(first, {name: 'get_stock', sku: 'KB100'}), '');
  assert.deepEqual(first, {name: 'find_products', maxPriceYuan: 200, feature: '静音'});
});

test('重复筛选按待返回调用匹配，工具链与日志无预写示例结果', () => {
  const original = {name: 'find_products', input: {maxPriceYuan: 200}, result: []};
  const next = {name: 'find_products', input: {maxPriceYuan: 250}, result: null};
  assert.equal(resultMatches(original, {name: 'find_products', result: []}), false);
  assert.equal(resultMatches(next, {name: 'find_products', result: []}), true);
  const source = readFileSync(new URL('../src/main/resources/web/app.js', import.meta.url), 'utf8');
  assert.match(source, /traceLog\(event\)/);
  assert.match(source, /queryChange\(previousQuery, input\)/);
  assert.doesNotMatch(source, /setInterval|Date\.now\(|stock: ?12|预算 200 → 250/);
  const html = readFileSync(new URL('../src/main/resources/web/index.html', import.meta.url), 'utf8');
  assert.match(html, /role="tablist"/);
  assert.match(html, /id="event-log" class="event-log"><\/pre>/);
});
