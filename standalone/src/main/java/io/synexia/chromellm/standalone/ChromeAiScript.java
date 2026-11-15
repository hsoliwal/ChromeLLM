/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 *
 * Session capability, download-monitor and user-activation mechanisms adapted from
 * GoogleChromeLabs/web-ai-demos at 2edcaedca4a13913598b6365d3c41b0f96e8d09d,
 * easy-language-model/src/create-session.js and user-activation.js.
 * Synexia changes: isolated sessions, bounded UTF-8 output, context admission,
 * cancellation, independent deadlines, late-resource cleanup and Java embedding.
 */
package io.synexia.chromellm.standalone;

/** Fixed browser program; caller data is supplied separately as a JSON request. */
public final class ChromeAiScript {
    private ChromeAiScript() {}

    public static String source() {
        return SOURCE;
    }

    private static final String SOURCE = """
            (() => {
              'use strict';
              if (Object.hasOwn(globalThis, 'chromeLlm')) {
                if (globalThis.chromeLlm?.apiVersion === 1) return true;
                throw new Error('CHROME_LLM_ALREADY_INSTALLED');
              }

              const ENGLISH_TEXT = Object.freeze({type: 'text', languages: Object.freeze(['en'])});
              const SESSION_OPTIONS = Object.freeze({
                expectedInputs: Object.freeze([ENGLISH_TEXT]),
                expectedOutputs: Object.freeze([ENGLISH_TEXT])
              });
              const AVAILABILITY = new Set(['available', 'downloadable', 'downloading', 'unavailable']);
              const MAX_INPUT_BYTES = 1048576;
              const MAX_OUTPUT_BYTES = 1048576;
              const PREPARE_TIMEOUT_MILLIS = 600000;
              const destroyed = new WeakSet();
              let downloadProgress = null;
              let cleanupFailed = false;
              let active = null;

              class BridgeFault extends Error {}
              const fault = code => new BridgeFault(code);
              const integer = value => Number.isSafeInteger(value) && value >= 0;
              const high = value => value >= 0xD800 && value <= 0xDBFF;
              const low = value => value >= 0xDC00 && value <= 0xDFFF;

              async function availability() {
                const secureContext = globalThis.isSecureContext === true;
                const result = {availability: 'unavailable', secureContext, downloadProgress};
                if (!secureContext) return {...result, error: 'INSECURE_CONTEXT'};
                if (cleanupFailed) return {...result, error: 'SESSION_CLEANUP_FAILED'};
                if (typeof globalThis.LanguageModel?.availability !== 'function' ||
                    typeof globalThis.LanguageModel?.create !== 'function') {
                  return {...result, error: 'PROMPT_API_UNSUPPORTED'};
                }
                try {
                  const value = await LanguageModel.availability(SESSION_OPTIONS);
                  if (!AVAILABILITY.has(value)) return {...result, error: 'INVALID_AVAILABILITY'};
                  return {...result, availability: value,
                    downloadProgress: value === 'available' ? 1 : downloadProgress};
                } catch (_) {
                  return {...result, error: 'PROMPT_API_ERROR'};
                }
              }

              function destroySession(session) {
                if ((typeof session !== 'object' && typeof session !== 'function') || !session) return;
                if (destroyed.has(session)) return;
                destroyed.add(session);
                try {
                  if (typeof session.destroy !== 'function') throw fault('SESSION_CLEANUP_FAILED');
                  session.destroy();
                } catch (_) {
                  // Require browser retirement; an uncertain native session is never reusable.
                  cleanupFailed = true;
                  if (active) stop(active, 'SESSION_CLEANUP_FAILED');
                }
              }

              function cancelStream(stream) {
                try {
                  if (typeof stream?.cancel === 'function') Promise.resolve(stream.cancel()).catch(() => {});
                } catch (_) {}
              }

              function cancelReader(operation) {
                if (!operation.reader || operation.readerCancelled) return;
                operation.readerCancelled = true;
                try { Promise.resolve(operation.reader.cancel()).catch(() => {}); } catch (_) {}
              }

              function stop(operation, code) {
                if (operation.closed || operation.reason) return false;
                operation.reason = code;
                operation.controller.abort(fault(code));
                cancelReader(operation);
                destroySession(operation.session);
                operation.session = null;
                operation.rejectStop(fault(code));
                return true;
              }

              function begin(id, timeoutMillis) {
                const operation = {id, controller: new AbortController(),
                  deadline: performance.now() + timeoutMillis, closed: false, reason: null,
                  session: null, reader: null, readerCancelled: false, availability: 'unavailable'};
                operation.stopped = new Promise((resolve, reject) => { operation.rejectStop = reject; });
                operation.stopped.catch(() => {});
                operation.timer = setTimeout(() => stop(operation, 'TIMEOUT'), timeoutMillis);
                active = operation;
                return operation;
              }

              function check(operation) {
                if (!operation.closed && !operation.reason && performance.now() >= operation.deadline) {
                  stop(operation, 'TIMEOUT');
                }
                if (operation.closed || operation.reason || operation.controller.signal.aborted) {
                  throw fault(operation.reason || 'CANCELLED');
                }
              }

              function wait(operation, work, disposeLate) {
                check(operation);
                const incoming = Promise.resolve().then(() => {
                  check(operation);
                  return work();
                }).then(value => {
                  if (operation.closed || operation.reason || operation.controller.signal.aborted) {
                    if (disposeLate) disposeLate(value);
                    throw fault(operation.reason || 'CANCELLED');
                  }
                  return value;
                });
                return Promise.race([incoming, operation.stopped]);
              }

              function finish(operation) {
                operation.closed = true;
                clearTimeout(operation.timer);
                operation.controller.abort();
                cancelReader(operation);
                if (operation.reader) {
                  try { operation.reader.releaseLock(); } catch (_) {}
                  operation.reader = null;
                }
                destroySession(operation.session);
                operation.session = null;
                if (active === operation) active = null;
              }

              function refused(error, operation) {
                const code = operation?.reason || (error instanceof BridgeFault ? error.message : 'PROMPT_API_ERROR');
                return {ok: false, error: code,
                  ...(operation ? {availability: operation.availability} : {})};
              }

              function validSession(session) {
                return session && typeof session.measureContextUsage === 'function' &&
                  typeof session.promptStreaming === 'function' && typeof session.destroy === 'function' &&
                  typeof session.addEventListener === 'function' &&
                  integer(session.contextWindow) && session.contextWindow > 0 &&
                  integer(session.contextUsage) && session.contextUsage <= session.contextWindow;
              }

              async function createSession(operation, allowDownload) {
                const status = await wait(operation, availability);
                check(operation);
                operation.availability = status.availability;
                if (status.error) throw fault(status.error);
                if (status.availability === 'unavailable') throw fault('MODEL_UNAVAILABLE');
                if (status.availability !== 'available') {
                  if (!allowDownload) throw fault('MODEL_NOT_READY');
                  if (globalThis.navigator?.userActivation?.isActive !== true) {
                    throw fault('USER_ACTIVATION_REQUIRED');
                  }
                }
                const session = await wait(operation, () => {
                  // Check at creation time as transient activation may have expired.
                  if (status.availability !== 'available' &&
                      globalThis.navigator?.userActivation?.isActive !== true) {
                    throw fault('USER_ACTIVATION_REQUIRED');
                  }
                  return LanguageModel.create({
                    ...SESSION_OPTIONS,
                    signal: operation.controller.signal,
                    monitor(monitor) {
                      monitor.addEventListener('downloadprogress', event => {
                        if (!operation.closed && !operation.reason &&
                            typeof event.loaded === 'number' && Number.isFinite(event.loaded) &&
                            event.loaded >= 0 && event.loaded <= 1) downloadProgress = event.loaded;
                      }, {signal: operation.controller.signal});
                    }
                  });
                }, destroySession);
                operation.session = session;
                check(operation);
                if (!validSession(session)) throw fault('UNSUPPORTED_SESSION');
                downloadProgress = 1;
                operation.availability = 'available';
                return session;
              }

              function validateRequest(value) {
                if (!value || typeof value !== 'object') throw fault('INVALID_REQUEST');
                const request = Object.freeze({id: value.id, prompt: value.prompt, maxTokens: value.maxTokens,
                  maxOutputBytes: value.maxOutputBytes, timeoutMillis: value.timeoutMillis,
                  allowDownload: value.allowDownload});
                if (typeof request.id !== 'string' || !request.id || typeof request.prompt !== 'string' ||
                    !request.prompt || !integer(request.maxTokens) || request.maxTokens === 0 ||
                    !integer(request.maxOutputBytes) || request.maxOutputBytes === 0 ||
                    request.maxOutputBytes > MAX_OUTPUT_BYTES ||
                    !integer(request.timeoutMillis) || request.timeoutMillis === 0 ||
                    request.timeoutMillis > 2147483647 || typeof request.allowDownload !== 'boolean') {
                  throw fault('INVALID_REQUEST');
                }
                let bytes = 0;
                for (let i = 0; i < request.prompt.length; i++) {
                  const unit = request.prompt.charCodeAt(i);
                  if (high(unit)) {
                    if (++i >= request.prompt.length || !low(request.prompt.charCodeAt(i))) {
                      throw fault('INVALID_REQUEST');
                    }
                    bytes += 4;
                  } else if (low(unit)) {
                    throw fault('INVALID_REQUEST');
                  } else {
                    bytes += unit <= 0x7F ? 1 : unit <= 0x7FF ? 2 : 3;
                  }
                  if (bytes > MAX_INPUT_BYTES) throw fault('INPUT_BYTES_EXCEEDED');
                }
                return request;
              }

              // Each delta is retained only after its complete UTF-8 size and scalar validity pass.
              // One trailing high surrogate may span chunks; it is never counted or emitted alone.
              function append(output, chunk, limit) {
                if (typeof chunk !== 'string') throw fault('MALFORMED_OUTPUT');
                if (!chunk.length) return;
                let bytes = output.bytes, index = 0, end = chunk.length, carry = '', prefix = '';
                if (output.high) {
                  if (!low(chunk.charCodeAt(0))) throw fault('MALFORMED_OUTPUT');
                  prefix = output.high;
                  bytes += 4;
                  index = 1;
                  if (bytes > limit) throw fault('OUTPUT_BYTES_EXCEEDED');
                }
                for (; index < chunk.length; index++) {
                  const unit = chunk.charCodeAt(index);
                  if (high(unit)) {
                    if (index + 1 === chunk.length) {
                      carry = chunk.charAt(index);
                      end = index;
                      break;
                    }
                    if (!low(chunk.charCodeAt(++index))) throw fault('MALFORMED_OUTPUT');
                    bytes += 4;
                  } else if (low(unit)) {
                    throw fault('MALFORMED_OUTPUT');
                  } else {
                    bytes += unit <= 0x7F ? 1 : unit <= 0x7FF ? 2 : 3;
                  }
                  if (bytes > limit) throw fault('OUTPUT_BYTES_EXCEEDED');
                }
                output.text += prefix + chunk.slice(0, end);
                output.bytes = bytes;
                output.high = carry;
              }

              async function prepare(allowDownload) {
                if (typeof allowDownload !== 'boolean') return {ok: false, ready: false, error: 'INVALID_REQUEST'};
                if (cleanupFailed) return {ok: false, ready: false, error: 'SESSION_CLEANUP_FAILED'};
                if (active) return {ok: false, ready: false, error: 'BUSY'};
                const operation = begin(null, PREPARE_TIMEOUT_MILLIS);
                try {
                  await createSession(operation, allowDownload);
                  check(operation);
                  return {ok: true, ready: true, availability: 'available', downloadProgress};
                } catch (error) {
                  return {...refused(error, operation), ready: false, downloadProgress};
                } finally {
                  finish(operation);
                  if (cleanupFailed) return {...refused(fault('SESSION_CLEANUP_FAILED'), operation), ready: false,
                    downloadProgress};
                }
              }

              async function infer(value) {
                let request;
                try { request = validateRequest(value); } catch (error) { return refused(error); }
                if (cleanupFailed) return {ok: false, error: 'SESSION_CLEANUP_FAILED'};
                if (active) return {ok: false, error: 'BUSY'};
                const operation = begin(request.id, request.timeoutMillis);
                try {
                  const session = await createSession(operation, request.allowDownload);
                  session.addEventListener('contextoverflow', () => stop(operation, 'CONTEXT_OVERFLOW'),
                    {signal: operation.controller.signal});
                  const inputTokens = await wait(operation, () => session.measureContextUsage(request.prompt));
                  check(operation);
                  if (!integer(inputTokens)) throw fault('INVALID_CONTEXT_MEASUREMENT');
                  const remaining = session.contextWindow - session.contextUsage;
                  if (inputTokens > remaining || request.maxTokens > remaining - inputTokens) {
                    throw fault('CONTEXT_BUDGET_EXCEEDED');
                  }
                  const stream = await wait(operation, () => session.promptStreaming(request.prompt,
                    {signal: operation.controller.signal}), cancelStream);
                  check(operation);
                  if (!stream || typeof stream.getReader !== 'function') throw fault('MALFORMED_OUTPUT');
                  operation.reader = stream.getReader();
                  const output = {text: '', bytes: 0, high: ''};
                  while (true) {
                    const part = await wait(operation, () => operation.reader.read());
                    check(operation);
                    if (!part || typeof part.done !== 'boolean') throw fault('MALFORMED_OUTPUT');
                    if (part.done) break;
                    append(output, part.value, request.maxOutputBytes);
                  }
                  if (output.high) throw fault('MALFORMED_OUTPUT');
                  // Chrome has no portable maxTokens generation option. Measure and refuse excess.
                  const measuredOutputTokens = await wait(operation, () => session.measureContextUsage(output.text));
                  check(operation);
                  if (!integer(measuredOutputTokens)) throw fault('INVALID_CONTEXT_MEASUREMENT');
                  if (measuredOutputTokens > request.maxTokens) throw fault('OUTPUT_TOKENS_EXCEEDED');
                  return {ok: true, text: output.text, measuredOutputTokens, availability: 'available'};
                } catch (error) {
                  return refused(error, operation);
                } finally {
                  finish(operation);
                  if (cleanupFailed) return refused(fault('SESSION_CLEANUP_FAILED'), operation);
                }
              }

              function cancel(id) {
                return {cancelled: typeof id === 'string' && active !== null && active.id === id &&
                  stop(active, 'CANCELLED')};
              }

              Object.defineProperty(globalThis, 'chromeLlm', {
                value: Object.freeze({apiVersion: 1, availability, prepare, infer, cancel}),
                enumerable: true, configurable: false, writable: false
              });
              return true;
            })()
            """;
}
