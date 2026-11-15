// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import { registerChromeLlmTools } from './register-chromellm-tools.mjs';

function fixture(overrides = {}) {
  const calls = { confirmed: 0, inferred: 0, status: 0 };
  const tools = new Map();
  const registrations = [];
  const modelContext = {
    async registerTool(tool, options) {
      registrations.push(options);
      if (overrides.rejectRegistration && registrations.length === 2) throw Error('disabled');
      tools.set(tool.name, tool);
    },
  };
  const callbacks = {
    modelContext,
    backendStatus: async () => { calls.status++; return { id: 'llama.cpp-jni' }; },
    confirmInference: async () => { calls.confirmed++; return true; },
    inferText: async ({ prompt }) => { calls.inferred++; return { text: `reply: ${prompt}` }; },
    ...overrides,
  };
  return { calls, tools, registrations, callbacks };
}

test('unsupported browser is a no-op', async () => {
  const result = await registerChromeLlmTools({ modelContext: null });
  assert.equal(result.supported, false);
  result.dispose();
});

test('tool lifecycle and synthetic inference without a browser engine', async () => {
  const { calls, tools, registrations, callbacks } = fixture();
  const adapter = await registerChromeLlmTools(callbacks);
  assert.equal(adapter.supported, true);
  assert.deepEqual([...tools.keys()], ['chromellm_backend_status', 'chromellm_infer_text']);
  assert.equal(tools.get('chromellm_backend_status').annotations.readOnlyHint, true);
  assert.equal(calls.inferred, 0);
  assert.deepEqual(await tools.get('chromellm_backend_status').execute({}), { backendId: 'llama.cpp-jni' });
  assert.equal(calls.inferred, 0);
  const result = await tools.get('chromellm_infer_text').execute({ prompt: 'नमस्ते', maxTokens: 12 });
  assert.deepEqual(result, { text: 'reply: नमस्ते' });
  assert.equal(calls.confirmed, 1);
  assert.equal(calls.inferred, 1);
  adapter.dispose();
  assert.ok(registrations.every((registration) => registration.signal.aborted));
});

test('invalid args, missing confirmation and aborts never invoke inference', async () => {
  const { calls, tools, callbacks } = fixture({ confirmInference: async () => false });
  await registerChromeLlmTools(callbacks);
  const infer = tools.get('chromellm_infer_text').execute;
  for (const args of [{ prompt: '' }, { prompt: '\ud800' }, { prompt: 'x', maxTokens: 0 },
    { prompt: 'x', timeoutSeconds: 3601 }, { prompt: 'x', modelUrl: 'https://evil.invalid' }]) {
    assert.deepEqual(await infer(args), { error: 'Invalid inference arguments' });
  }
  assert.deepEqual(await infer({ prompt: 'x' }), { error: 'Inference was not authorized' });
  assert.deepEqual(await infer({ prompt: 'x' }, { signal: AbortSignal.abort() }), { error: 'Cancelled' });
  assert.equal(calls.inferred, 0);
});

test('errors are generic, registration failures unregister, missing callbacks reject', async () => {
  const { tools, callbacks } = fixture({
    inferText: async () => { throw Error('secret key details'); },
  });
  const adapter = await registerChromeLlmTools(callbacks);
  assert.deepEqual(await tools.get('chromellm_infer_text').execute({ prompt: 'x' }),
    { error: 'Model inference failed' });
  adapter.dispose();
  const broken = fixture({ rejectRegistration: true });
  await assert.rejects(registerChromeLlmTools(broken.callbacks), /disabled/);
  assert.ok(broken.registrations[0].signal.aborted);
  await assert.rejects(registerChromeLlmTools({ modelContext: broken.callbacks.modelContext }),
    /requires explicit/);
});
