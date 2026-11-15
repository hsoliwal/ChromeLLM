'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup({ available = 'available', nativeError = null, clipboardFailure = false } = {}) {
  const handlers = new Map();
  const els = new Map();
  for (const id of ['repo', 'status', 'result', 'inspect', 'propose', 'copy']) {
    els.set(id, {
      value: id === 'repo' ? 'ChromeLLM' : undefined,
      textContent: '', disabled: id === 'copy',
      addEventListener(kind, cb) { handlers.set(`${id}:${kind}`, cb); }
    });
  }
  const calls = { native: [], available: 0, create: 0, prompt: 0, destroy: 0, copied: [] };
  const ctx = {
    document: { getElementById: id => els.get(id) },
    navigator: { clipboard: {
      async writeText(value) {
        if (clipboardFailure) throw new Error('not allowed');
        calls.copied.push(value);
      }
    } },
    chrome: { runtime: {
      lastError: null,
      sendNativeMessage(host, payload, callback) {
        calls.native.push({ host, payload });
        this.lastError = nativeError ? { message: nativeError } : null;
        callback(nativeError ? undefined : {
          ok: true, repo: 'ChromeLLM', files: [{ path: 'README.md', sha256: 'abcdef', bytes: 3 }]
        });
      }
    } },
    LanguageModel: {
      async availability() { calls.available++; return available; },
      async create({ monitor }) {
        calls.create++;
        monitor({ addEventListener(name, callback) { if (name === 'downloadprogress') callback({ loaded: 1 }); } });
        return {
          async prompt(input) { calls.prompt++; assert.match(input, /sha256/); return 'Advisory plan: java-only'; },
          destroy() { calls.destroy++; }
        };
      }
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'popup.js'), 'utf8'), ctx);
  return { els, handlers, calls };
}

test('nothing is downloaded or inspected before a real click handler', () => {
  const { calls } = setup();
  assert.equal(calls.native.length, 0);
  assert.equal(calls.available, 0);
  assert.equal(calls.create, 0);
});

test('inspection only requests the allowlisted inventory', async () => {
  const { els, handlers, calls } = setup();
  await handlers.get('inspect:click')();
  assert.equal(calls.native.length, 1);
  assert.equal(calls.native[0].payload.op, 'inventory');
  assert.equal(calls.native[0].payload.repo, 'ChromeLLM');
  assert.equal(calls.create, 0);
  assert.match(els.get('result').textContent, /README\.md/);
});

test('user-clicked Chrome AI generates advisory text with no executable action', async () => {
  const { els, handlers, calls } = setup();
  await handlers.get('propose:click')();
  assert.equal(calls.available, 1);
  assert.equal(calls.create, 1);
  assert.equal(calls.prompt, 1);
  assert.equal(calls.destroy, 1);
  assert.equal(calls.native.length, 1);
  assert.equal(els.get('result').textContent, 'Advisory plan: java-only');
  assert.match(els.get('status').textContent, /No build executed/);
});

test('unavailable browser model stops without native or fallback call', async () => {
  const { els, handlers, calls } = setup({ available: 'unavailable' });
  await handlers.get('propose:click')();
  assert.equal(calls.native.length, 0);
  assert.equal(calls.create, 0);
  assert.match(els.get('status').textContent, /unavailable/);
});

test('uninstalled native host produces explicit failure', async () => {
  const { els, handlers, calls } = setup({ nativeError: 'Specified native messaging host not found' });
  await handlers.get('inspect:click')();
  assert.equal(calls.native.length, 1);
  assert.match(els.get('status').textContent, /failed/);
});

test('copy requires reviewed AI output and never writes to the host', async () => {
  const { els, handlers, calls } = setup();
  assert.equal(els.get('copy').disabled, true);
  await handlers.get('copy:click')();
  assert.equal(calls.copied.length, 0);
  await handlers.get('propose:click')();
  assert.equal(els.get('copy').disabled, false);
  await handlers.get('copy:click')();
  assert.deepEqual(calls.copied, ['Advisory plan: java-only']);
  assert.equal(calls.native.length, 1);
  await handlers.get('inspect:click')();
  assert.equal(els.get('copy').disabled, true);
  await handlers.get('copy:click')();
  assert.equal(calls.copied.length, 1);
});

test('clipboard denial is reported without native file writes', async () => {
  const { els, handlers, calls } = setup({ clipboardFailure: true });
  await handlers.get('propose:click')();
  await handlers.get('copy:click')();
  assert.match(els.get('status').textContent, /Copy failed/);
  assert.equal(calls.copied.length, 0);
  assert.equal(calls.native.length, 1);
});
