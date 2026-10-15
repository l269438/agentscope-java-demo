import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';

test('README 二维码引用随仓库提供的完整原图', () => {
  const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');
  assert.ok(readme.includes('![微信公众号「雷一言说」二维码](assets/wechat-qr.png)'));
  const png = readFileSync(new URL('../assets/wechat-qr.png', import.meta.url));
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal(png.readUInt32BE(16), 430);
  assert.equal(png.readUInt32BE(20), 430);
  const manifest = JSON.parse(readFileSync(new URL('../oss-upload-manifest.json', import.meta.url), 'utf8'));
  assert.equal(createHash('sha256').update(png).digest('hex'), manifest.entries['assets/wechat-qr.png'].hash);
});
