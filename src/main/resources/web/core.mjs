/** 增量解析SSE，兼容任意网络分片。 */
export class SseFrames {
  constructor(onFrame) { this.buffer = ''; this.onFrame = onFrame; }
  push(text) {
    this.buffer += text;
    for (;;) {
      const match = /\r?\n\r?\n/.exec(this.buffer);
      if (!match) return;
      const frame = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      let name = 'message';
      const data = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) name = line.slice(6).trim();
        if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) this.onFrame(name, JSON.parse(data.join('\n')));
    }
  }
  finish() {
    if (this.buffer.trim()) throw new Error('执行事件流未完整结束');
  }
}

export const EXAMPLES = Object.freeze({
  stock: '预算200元，想买适合办公室的静音键盘。只推荐有现货的商品，并说明价格、特点和库存。',
  empty: '预算100元，想买静音键盘，只推荐有现货的商品。'
});

export function resultMatches(step, payload) {
  return step.name === payload.name && !step.result &&
    (payload.name !== 'get_stock' || step.input.sku === payload.result?.sku);
}

export function replyComplete(data) {
  return typeof data?.reply?.text === 'string' && data.reply.text.trim().length > 0
    && data.reply.stageSummary === false && data.reply.generateReason === 'MODEL_STOP';
}

const TRACE_TYPES = new Set(['MODEL_CALL_START', 'java_tool_input', 'java_tool_output', 'final_reply', 'failure', 'run_end']);

export function traceVisible(event) { return TRACE_TYPES.has(event?.type); }

/** 时钟取服务端事件时间，固定显示北京时间；不生成客户端伪时间。 */
export function traceTime(event) {
  if (typeof event?.time !== 'string') return '—';
  const time = new Date(event.time);
  if (Number.isNaN(time.getTime())) return '—';
  return new Intl.DateTimeFormat('en-GB', {timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit',
    second: '2-digit', fractionalSecondDigits: 3, hourCycle: 'h23'}).format(time);
}

export function traceSequence(event) {
  return Number.isInteger(event?.sequence) ? `#${String(event.sequence).padStart(4, '0')}` : '序号未提供';
}

export function traceLog(event) {
  if (!traceVisible(event)) return '';
  return `${traceSequence(event)} ${traceTime(event)} [${event.type}]\n${JSON.stringify(event.payload, null, 2)}\n\n`;
}

/** 只比较实际相邻筛选调用，不推断模型动机或用户授权。 */
export function queryChange(previous, current) {
  if (previous?.name !== 'find_products' || current?.name !== 'find_products') return '';
  const changes = [];
  if (previous.maxPriceYuan !== current.maxPriceYuan) changes.push(`预算 ${previous.maxPriceYuan} → ${current.maxPriceYuan} 元`);
  if (previous.feature !== current.feature) changes.push(`特点 ${previous.feature} → ${current.feature}`);
  return changes.length ? `查询条件变化：${changes.join('；')}` : '';
}
