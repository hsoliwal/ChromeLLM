// SPDX-License-Identifier: MIT

const MAX_PROMPT_BYTES = 16 * 1024 * 1024;
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const encoder = new TextEncoder();

function boundedText(text, maxBytes) {
  if (typeof text !== 'string' || text.trim().length === 0 || text.length > maxBytes) {
    throw new TypeError('Text must be nonempty and within its configured byte limit');
  }
  for (let i = 0; i < text.length; i++) {
    const current = text.charCodeAt(i);
    if (current >= 0xD800 && current <= 0xDBFF) {
      const following = text.charCodeAt(++i);
      if (!(following >= 0xDC00 && following <= 0xDFFF)) {
        throw new TypeError('Malformed UTF-16 is not admitted');
      }
    } else if (current >= 0xDC00 && current <= 0xDFFF) {
      throw new TypeError('Malformed UTF-16 is not admitted');
    }
  }
  if (encoder.encode(text).byteLength > maxBytes) {
    throw new TypeError('Text exceeds the configured byte limit');
  }
  return text;
}

function request(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) ||
      Object.keys(input).some((key) => !['prompt', 'maxTokens', 'timeoutSeconds'].includes(key))) {
    throw new TypeError('Invalid inference parameters');
  }
  const prompt = boundedText(input.prompt, MAX_PROMPT_BYTES);
  const maxTokens = input.maxTokens ?? 512;
  const timeoutSeconds = input.timeoutSeconds ?? 120;
  if (!Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > 32768 ||
      !Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 3600) {
    throw new TypeError('Invalid inference budget');
  }
  return Object.freeze({ prompt, maxTokens, timeoutSeconds });
}

/** Feature-detected WebMCP tools; callbacks bind to a pre-authorized host runtime. */
export async function registerChromeLlmTools({
  modelContext = globalThis.document?.modelContext,
  backendStatus,
  inferText,
  confirmInference,
} = {}) {
  if (!modelContext || typeof modelContext.registerTool !== 'function') {
    return { supported: false, dispose() {} };
  }
  if (typeof backendStatus !== 'function' || typeof inferText !== 'function' ||
      typeof confirmInference !== 'function') {
    throw new TypeError('WebMCP requires explicit backend and user-confirmation callbacks');
  }

  const controller = new AbortController();
  const registration = { signal: controller.signal };
  try {
    await modelContext.registerTool({
      name: 'chromellm_backend_status',
      description: 'Read the selected ChromeLLM provider ID without opening its model.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      async execute(input, { signal } = {}) {
        if (input && Object.keys(input).length !== 0) return { error: 'Unexpected arguments' };
        try {
          if (signal?.aborted) return { error: 'Cancelled' };
          const status = await backendStatus({ signal });
          if (!status || typeof status.id !== 'string' || !status.id.trim()) {
            throw new TypeError('Invalid backend identity');
          }
          return { backendId: status.id };
        } catch {
          return { error: 'Backend status unavailable' };
        }
      },
      annotations: { readOnlyHint: true },
    }, registration);

    await modelContext.registerTool({
      name: 'chromellm_infer_text',
      description: 'Run explicitly authorized inference with the configured ChromeLLM backend.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          prompt: { type: 'string', minLength: 1 },
          maxTokens: { type: 'integer', minimum: 1, maximum: 32768, default: 512 },
          timeoutSeconds: { type: 'integer', minimum: 1, maximum: 3600, default: 120 },
        },
        required: ['prompt'],
      },
      async execute(input, { signal } = {}) {
        let params;
        try { params = request(input); }
        catch { return { error: 'Invalid inference arguments' }; }
        if (signal?.aborted) return { error: 'Cancelled' };
        try {
          const authorized = await confirmInference(params, { signal });
          if (authorized !== true) return { error: 'Inference was not authorized' };
          if (signal?.aborted) return { error: 'Cancelled' };
          const result = await inferText(params, { signal });
          return { text: boundedText(result?.text, MAX_RESPONSE_BYTES) };
        } catch {
          return { error: 'Model inference failed' };
        }
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    }, registration);
    return { supported: true, dispose() { controller.abort(); } };
  } catch (error) {
    controller.abort();
    throw error;
  }
}
