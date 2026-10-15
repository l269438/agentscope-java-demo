import {SseFrames, EXAMPLES, resultMatches, replyComplete, traceVisible, traceTime, traceSequence, traceLog, queryChange} from '/core.mjs';

const $ = id => document.getElementById(id);
let busy = false;
let ready = false;
let catalog = [];
let steps = [];
let calls = 0;
let runId = '';
let configuration = null;
let assistant = null;
let eventCount = 0;
let previousQuery = null;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = String(text);
  return node;
}

function controls() {
  $('send').disabled = busy || !ready;
  $('message').disabled = busy;
  $('new-query').disabled = busy;
  for (const button of document.querySelectorAll('[data-example]')) button.disabled = busy || !ready;
  $('send').firstChild.textContent = busy ? '执行中 ' : '发送 ';
}

function status(text, state = '') {
  $('status-label').textContent = text;
  $('run-status').className = `run-status ${state}`;
  $('model-count').textContent = calls ? `${calls} 次模型请求` : '';
}

function renderCatalog() {
  $('catalog').replaceChildren();
  $('catalog-count').textContent = `${catalog.length} 款模拟商品`;
  for (const product of catalog) {
    const row = element('div', 'product-row');
    row.dataset.sku = product.sku;
    const top = element('div', 'product-top');
    top.append(element('span', 'product-name', product.name), element('span', 'product-price', `¥${product.priceYuan}`));
    row.append(top, element('div', 'product-detail', `${product.sku} · ${(product.features || []).join(' / ')}`),
      element('span', 'stock-label', '库存待查询'));
    $('catalog').append(row);
  }
}

function reset() {
  steps = []; calls = 0; runId = ''; assistant = null; eventCount = 0; previousQuery = null;
  $('thread').replaceChildren();
  $('tools').replaceChildren();
  $('event-log').replaceChildren();
  $('event-count').textContent = '0 条事件';
  $('tools-hint').hidden = false;
  $('tools-hint').textContent = '发送查询后，按发生顺序显示模型请求、工具调用和返回结果。';
  $('tool-count').textContent = '0 次工具调用';
  $('run-meta').hidden = true;
  $('finish-reason').textContent = '';
  renderCatalog();
  status('准备好开始查询');
}

function welcome() {
  const empty = element('div', 'empty-state');
  empty.append(element('span', 'empty-arrow', '↗'), element('h3', '', '告诉我预算和键盘特点'),
    element('p', '', '先筛选商品，再查询库存，最后根据工具结果给出推荐。'));
  $('thread').append(empty);
}

// 只识别文本强调与列表。所有模型文字通过Text节点写入，不执行HTML。
function inline(text, container) {
  for (const part of text.split(/(\*\*[^*]+\*\*)/g)) {
    if (part.startsWith('**') && part.endsWith('**')) container.append(element('strong', '', part.slice(2, -2)));
    else container.append(document.createTextNode(part));
  }
}

function renderReply(text) {
  if (!assistant) {
    assistant = element('article', 'assistant-message');
    const heading = element('div', 'assistant-heading');
    heading.append(element('span', 'assistant-avatar', 'A↗'), element('span', '', '电商助手'));
    assistant.append(heading, element('div', 'reply'));
    $('thread').append(assistant);
  }
  const reply = assistant.querySelector('.reply');
  reply.replaceChildren();
  let list = null;
  for (const original of text.split('\n')) {
    const line = original.trim();
    if (!line) { list = null; continue; }
    if (/^[-*] /.test(line)) {
      if (!list) { list = element('ul'); reply.append(list); }
      const item = element('li'); inline(line.slice(2), item); list.append(item);
    } else {
      list = null;
      const note = line.startsWith('>');
      const paragraph = element('p', note ? 'reply-note' : '');
      inline(note ? line.replace(/^>\s?/, '') : line, paragraph);
      reply.append(paragraph);
    }
  }
}

function failure(message) {
  const warning = element('p', 'reply-error', message);
  $('thread').append(warning);
  status('本次查询未完成', 'failed');
  for (const step of steps) {
    if (!step.result) {
      step.node.classList.replace('pending', 'unresolved');
      step.description.textContent = '未收到成功返回';
    }
  }
}

function eventMeta(event) {
  return element('span', 'event-meta', `${traceSequence(event)} · ${traceTime(event)}`);
}

function chainNote(event, text, state = '') {
  const item = element('li', `chain-entry chain-note ${state}`);
  item.append(element('span', 'chain-marker'), element('span', 'chain-note-text', text), eventMeta(event));
  $('tools').append(item);
}

function toolStarted(event) {
  const input = event.payload;
  const step = {name: input.name, input, result: null};
  const item = element('li', 'chain-entry tool-step pending');
  const heading = element('div', 'tool-heading');
  const title = input.name === 'find_products' ? '筛选商品' : input.name === 'get_stock' ? `查询库存 · ${input.sku}` : input.name;
  heading.append(element('strong', '', title), eventMeta(event));
  const description = element('p', 'tool-description', '等待工具返回…');
  const detail = element('details', 'tool-detail');
  detail.append(element('summary', '', '参数与返回 JSON'), element('pre', 'tool-json', JSON.stringify(input, null, 2)));
  const change = queryChange(previousQuery, input);
  if (input.name === 'find_products') previousQuery = input;
  const argumentsText = input.name === 'find_products' ? `maxPriceYuan=${input.maxPriceYuan}, feature="${input.feature}"` : `sku="${input.sku}"`;
  item.append(element('span', 'chain-marker', String(steps.length + 1)), heading,
    element('code', 'tool-call', `${input.name}(${argumentsText})`));
  if (change) item.append(element('p', 'query-change', change));
  item.append(description, detail);
  $('tools').append(item);
  $('tools-hint').hidden = true;
  Object.assign(step, {node: item, description, detail});
  steps.push(step);
  $('tool-count').textContent = `${steps.length} 次工具调用`;
  status(input.name === 'find_products' ? '正在筛选商品' : `正在查询 ${input.sku} 库存`, 'running');
}

function toolFinished(event) {
  const payload = event.payload;
  const step = steps.find(item => resultMatches(item, payload));
  if (!step) throw new Error('工具返回缺少对应调用');
  step.result = payload.result;
  step.node.classList.replace('pending', 'done');
  step.description.classList.add('result');
  step.detail.append(element('span', 'result-meta', `返回 ${traceSequence(event)} · ${traceTime(event)}`),
    element('pre', 'tool-json', JSON.stringify(payload.result, null, 2)));
  if (payload.name === 'find_products') {
    if (!Array.isArray(payload.result)) throw new Error('商品查询结果格式不正确');
    step.description.textContent = payload.result.length ? `返回 → ${payload.result.map(product => `${product.sku} / ¥${product.priceYuan}`).join('，')}` : '返回 → []，没有符合条件的商品';
    for (const product of payload.result) {
      const row = [...$('catalog').children].find(row => row.dataset.sku === product.sku);
      if (row) row.classList.add('matched');
    }
  } else if (payload.name === 'get_stock') {
    const stock = payload.result;
    step.description.textContent = `返回 → stock=${stock.stock}, inStock=${stock.inStock} · ${stock.inStock ? '有现货' : '缺货'}`;
    const row = [...$('catalog').children].find(row => row.dataset.sku === stock.sku);
    if (row) {
      const label = row.querySelector('.stock-label');
      label.textContent = `库存 ${stock.stock} 件 · ${stock.inStock ? '有现货' : '缺货'}`;
      label.className = `stock-label ${stock.inStock ? 'available' : 'unavailable'}`;
    }
  }
}

function showTrace(event) {
  if (!traceVisible(event)) return;
  $('tools-hint').hidden = true;
  eventCount++;
  $('event-count').textContent = `${eventCount} 条事件`;
  $('event-log').append(document.createTextNode(traceLog(event)));
  if (event.type === 'MODEL_CALL_START') {
    calls++;
    chainNote(event, `模型请求 #${calls}`);
    status(`第 ${calls} 次模型请求`, 'running');
  }
  if (event.type === 'java_tool_input') toolStarted(event);
  if (event.type === 'java_tool_output') toolFinished(event);
  if (event.type === 'final_reply') {
    renderReply(event.payload.text);
    chainNote(event, `收到最终回复 · ${event.payload.generateReason}`);
  }
  if (event.type === 'failure') chainNote(event, `执行失败 · ${event.payload.error}`, 'failed');
  if (event.type === 'run_end') chainNote(event, `执行结束 · ${event.payload.status}`, event.payload.status === 'completed' ? 'done' : 'failed');
}

function selectTraceView(view, focus = false) {
  for (const button of document.querySelectorAll('[data-trace-view]')) {
    const selected = button.dataset.traceView === view;
    button.setAttribute('aria-selected', String(selected));
    button.tabIndex = selected ? 0 : -1;
    $(button.getAttribute('aria-controls')).hidden = !selected;
    if (selected && focus) button.focus();
  }
}

for (const button of document.querySelectorAll('[data-trace-view]')) {
  button.addEventListener('click', () => selectTraceView(button.dataset.traceView));
  button.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const view = event.key === 'Home' ? 'chain' : event.key === 'End' ? 'log' : button.dataset.traceView === 'chain' ? 'log' : 'chain';
    selectTraceView(view, true);
  });
}

function showIdentity(data) {
  runId = data.runId;
  $('run-meta').hidden = false;
  $('run-id').textContent = runId;
  configuration = data.configuration;
  $('model-name').textContent = configuration.model;
  $('model-provider').textContent = `${configuration.provider === 'ollama' ? 'Ollama · 本地模型' : 'DashScope · 云模型'} · SDK ${configuration.sdkVersion}`;
}

async function sendQuery(message) {
  if (busy || !ready) return;
  busy = true; controls(); reset();
  $('thread').append(element('div', 'user-message', message));
  status('正在请求模型', 'running');
  let completed = false;
  let streamError = null;
  try {
    const response = await fetch('/api/react/chat', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({message})});
    if (!response.ok) {
      const error = await response.json();
      if (error.runId) { runId = error.runId; $('run-id').textContent = runId; $('run-meta').hidden = false; }
      throw new Error(error.message || `服务返回 HTTP ${response.status}`);
    }
    if (!response.headers.get('content-type')?.startsWith('text/event-stream') || !response.body) throw new Error('未收到执行事件流');
    const frames = new SseFrames((name, data) => {
      if (name === 'run') showIdentity(data);
      if (name === 'trace') {
        if (data.runId !== runId) throw new Error('执行事件编号不匹配');
        showTrace(data);
      }
      if (name === 'error') streamError = data.message || '模型执行失败，未自动重试。';
      if (name === 'done') {
        if (data.runId !== runId) throw new Error('最终结果编号不匹配');
        completed = true;
        calls = data.modelCalls;
        renderReply(data.reply.text);
        const normal = replyComplete(data);
        status(normal ? '查询已完成' : data.reply.stageSummary ? '已达到迭代上限，请查看阶段性回复' : '未收到有效最终回复，请查看结束状态', normal ? 'completed' : 'failed');
        $('finish-reason').textContent = normal ? '模型正常结束 · MODEL_STOP' : `结束原因：${data.reply.generateReason}`;
        for (const step of steps) if (!step.result) {
          step.node.classList.replace('pending', 'unresolved');
          step.description.textContent = '未收到成功返回';
        }
      }
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        frames.push(decoder.decode(chunk.value, {stream: true}));
      }
      frames.push(decoder.decode()); frames.finish();
    } finally { reader.releaseLock(); }
    if (streamError) throw new Error(streamError);
    if (!completed) throw new Error('连接中断，未收到最终结果；任务可能仍在执行，未自动重试。');
  } catch (error) {
    failure(error instanceof Error ? error.message : '连接失败，未自动重试。');
  } finally { busy = false; controls(); }
}

$('query-form').addEventListener('submit', event => {
  event.preventDefault();
  const message = $('message').value.trim();
  if (message && message.length <= 1500) sendQuery(message);
});
$('message').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault(); if (!busy && ready) $('query-form').requestSubmit();
  }
});
for (const button of document.querySelectorAll('[data-example]')) button.addEventListener('click', () => {
  if (!busy && ready) { $('message').value = EXAMPLES[button.dataset.example]; $('message').focus(); }
});
$('new-query').addEventListener('click', () => {
  if (busy) return;
  reset(); welcome(); $('message').value = ''; $('message').focus();
});

controls();
try {
  const [healthResponse, catalogResponse] = await Promise.all([fetch('/health', {cache: 'no-store'}), fetch('/api/react/catalog', {cache: 'no-store'})]);
  if (!healthResponse.ok || !catalogResponse.ok) throw new Error('本地服务加载失败');
  const health = await healthResponse.json();
  const products = await catalogResponse.json();
  if (products.dataType !== 'simulated' || !Array.isArray(products.catalog)) throw new Error('商品数据类型不正确');
  catalog = products.catalog;
  configuration = health.configuration;
  $('model-name').textContent = configuration.model;
  $('model-provider').textContent = `${configuration.provider === 'ollama' ? 'Ollama · 本地模型' : 'DashScope · 云模型'} · SDK ${configuration.sdkVersion}`;
  ready = configuration.modelConfigurationReady === true;
  $('connection-dot').classList.toggle('ready', ready);
  renderCatalog();
  if (!ready) status('模型未配置，请在本机配置后重启服务', 'failed');
  if (configuration.provider !== 'ollama') $('usage-note').textContent = '每次发送独立查询；当前云模型按提供商规则计费。';
} catch (error) {
  $('model-name').textContent = '本地服务未就绪';
  status(error.message, 'failed');
}
controls();
