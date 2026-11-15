/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Executes the actual embedded browser program against a controlled native API boundary. */
public final class ChromeAiScriptContractTestMain {
    private ChromeAiScriptContractTestMain() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("chromellm-script-contract-");
        Process process = null;
        try {
            Path source = directory.resolve("bridge.js");
            Path test = directory.resolve("contract.cjs");
            Files.writeString(source, ChromeAiScript.source(), StandardCharsets.UTF_8);
            Files.writeString(test, HARNESS, StandardCharsets.UTF_8);
            String node = System.getProperty("chromellm.test.node", "node");
            process = new ProcessBuilder(node, "--unhandled-rejections=strict", test.toString(),
                    source.toString(), args.length == 0 ? "" : args[0]).inheritIO().start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Browser script contract harness exceeded 45 seconds");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError("Browser script contract harness failed: " + process.exitValue());
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static final String HARNESS = """
            'use strict';
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const vm = require('node:vm');
            const source = fs.readFileSync(process.argv[2], 'utf8');
            const filter = new RegExp(process.argv[3] || '');
            const HIGH = String.fromCharCode(0xD83D);
            const LOW = String.fromCharCode(0xDE42);
            const tick = () => new Promise(resolve => setImmediate(resolve));
            const deferred = () => {
              let resolve, reject;
              const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
              return {promise, resolve, reject};
            };
            const request = changes => Object.assign({
              id: 'r1', prompt: 'explain this build failure', maxTokens: 8,
              maxOutputBytes: 64, timeoutMillis: 100, allowDownload: false
            }, changes);
            const failure = (result, code) => {
              assert.equal(result.ok, false, 'request must fail');
              assert.equal(result.error, code);
              assert.equal(Object.hasOwn(result, 'text'), false, 'failure must not return partial text');
            };
            function fixture(configuration = {}) {
              const state = {availabilityCalls: 0, creates: 0, sessions: [],
                availabilityOptions: [], createOptions: [], prompts: [], measurements: [], streamCancels: 0};
              let now = 0, nextTimer = 0;
              const timers = new Map();
              class Session extends EventTarget {
                constructor() {
                  super();
                  this.contextWindow = configuration.window ?? 128;
                  this.contextUsage = configuration.usage ?? 0;
                  this.destroyCalls = 0;
                  this.measurements = 0;
                  state.sessions.push(this);
                }
                measureContextUsage(text) {
                  state.measurements.push(text);
                  this.measurements++;
                  if (configuration.measure) return configuration.measure(text, this.measurements, this);
                  return Promise.resolve(this.measurements === 1 ?
                    (configuration.inputTokens ?? 4) : (configuration.outputTokens ?? 2));
                }
                promptStreaming(prompt, options) {
                  state.prompts.push({prompt, options});
                  if (configuration.stream) return configuration.stream(this, options);
                  const session = this;
                  return new ReadableStream({
                    start(controller) {
                      for (const chunk of (configuration.chunks ?? ['ok'])) controller.enqueue(chunk);
                      if (configuration.overflow) session.dispatchEvent(new Event('contextoverflow'));
                      if (!configuration.stall) controller.close();
                    },
                    cancel() { state.streamCancels++; }
                  });
                }
                clone() { throw new Error('An isolated request must not clone or reuse another session'); }
                destroy() {
                  this.destroyCalls++;
                  if (configuration.destroyFails) throw new Error('native destroy failed');
                }
              }
              const api = {
                availability(options) {
                  state.availabilityCalls++;
                  state.availabilityOptions.push(options);
                  return typeof configuration.availability === 'function' ?
                    configuration.availability(options) : Promise.resolve(configuration.availability ?? 'available');
                },
                create(options) {
                  state.creates++;
                  state.createOptions.push(options);
                  const monitor = new EventTarget();
                  options.monitor?.(monitor);
                  if (configuration.progress !== undefined) {
                    monitor.dispatchEvent(Object.assign(new Event('downloadprogress'), {loaded: configuration.progress}));
                  }
                  return configuration.create ? configuration.create(options, () => new Session()) :
                    Promise.resolve(new Session());
                }
              };
              const context = vm.createContext({
                console, AbortController, Event, EventTarget, DOMException, ReadableStream, TextEncoder,
                isSecureContext: configuration.secure !== false,
                navigator: {userActivation: {isActive: configuration.activation === true}},
                performance: {now: () => now},
                setTimeout: (callback, delay) => {
                  const id = ++nextTimer; timers.set(id, {callback, at: now + delay}); return id;
                },
                clearTimeout: id => timers.delete(id),
                ...(configuration.unsupported ? {} : {LanguageModel: api})
              });
              vm.runInContext(source, context, {timeout: 1000});
              assert.equal(typeof context.chromeLlm?.availability, 'function', 'script must install capability probe');
              return {
                bridge: context.chromeLlm, state, context, session: () => new Session(),
                advance(milliseconds) {
                  now += milliseconds;
                  for (const [id, timer] of [...timers]) {
                    if (timer.at <= now) { timers.delete(id); timer.callback(); }
                  }
                },
                timerCount: () => timers.size,
                reinstall: () => vm.runInContext(source, context, {timeout: 1000})
              };
            }
            const cases = [];
            const test = (name, run) => cases.push({name, run});

            test('status is a pure capability probe', async () => {
              const f = fixture();
              const status = await f.bridge.availability();
              assert.equal(status.availability, 'available');
              assert.equal(status.secureContext, true);
              assert.equal(f.state.availabilityCalls, 1);
              assert.equal(f.state.creates, 0);
              assert.equal(f.state.prompts.length, 0);
            });
            test('insecure and missing APIs are unavailable without native calls', async () => {
              for (const configuration of [{secure: false}, {unsupported: true}]) {
                const f = fixture(configuration);
                const status = await f.bridge.availability();
                assert.equal(status.availability, 'unavailable');
                assert.equal(f.state.availabilityCalls, 0);
                assert.equal(f.state.creates, 0);
                assert.equal(typeof status.error, 'string');
              }
            });
            test('status rejects unrecognized native availability states', async () => {
              const f = fixture({availability: 'pretend-ready'});
              const status = await f.bridge.availability();
              assert.equal(status.availability, 'unavailable');
              assert.equal(status.error, 'INVALID_AVAILABILITY');
            });
            test('prepare refuses implicit downloads and requires actual user activation', async () => {
              const f = fixture({availability: 'downloadable'});
              failure(await f.bridge.prepare(false), 'MODEL_NOT_READY');
              failure(await f.bridge.prepare(true), 'USER_ACTIVATION_REQUIRED');
              assert.equal(f.state.creates, 0);
            });
            test('prepare explicitly downloads then destroys its warmup session', async () => {
              const f = fixture({availability: 'downloadable', activation: true, progress: 0.5});
              const result = await f.bridge.prepare(true);
              assert.equal(result.ok, true);
              assert.equal(result.ready, true);
              assert.equal(result.availability, 'available');
              assert.equal(result.downloadProgress, 1);
              assert.equal(f.state.sessions[0].destroyCalls, 1);
              assert.equal(f.state.prompts.length, 0);
              assert.equal(f.timerCount(), 0);
            });
            test('prepare and inference pass identical English capability options', async () => {
              const f = fixture();
              assert.equal((await f.bridge.prepare(false)).ok, true);
              const result = await f.bridge.infer(request());
              assert.equal(result.text, 'ok');
              for (const options of [...f.state.availabilityOptions, ...f.state.createOptions]) {
                assert.equal(JSON.stringify(options.expectedInputs), '[{"type":"text","languages":["en"]}]');
                assert.equal(JSON.stringify(options.expectedOutputs), '[{"type":"text","languages":["en"]}]');
                assert.equal(Object.hasOwn(options, 'maxTokens'), false);
                assert.equal(Object.hasOwn(options, 'maxOutputTokens'), false);
              }
              assert.equal(f.state.createOptions[0].signal instanceof AbortSignal, true);
              assert.equal(Object.hasOwn(f.state.prompts[0].options, 'maxTokens'), false);
              assert.equal(Object.hasOwn(f.state.prompts[0].options, 'maxOutputTokens'), false);
            });
            test('repeated-prefix deltas are appended without deduplication', async () => {
              const f = fixture({chunks: ['ha', 'ha', 'haha']});
              const result = await f.bridge.infer(request());
              assert.equal(result.ok, true);
              assert.equal(result.text, 'hahahaha');
              assert.equal(result.measuredOutputTokens, 2);
              assert.equal(f.state.measurements[1], 'hahahaha');
            });
            test('UTF8 limit counts split surrogate pairs exactly', async () => {
              const f = fixture({chunks: ['A' + HIGH, '', LOW, 'B']});
              assert.equal((await f.bridge.infer(request({maxOutputBytes: 6}))).text, 'A🙂B');
              const small = fixture({chunks: ['A' + HIGH, LOW, 'B']});
              failure(await small.bridge.infer(request({maxOutputBytes: 5})), 'OUTPUT_BYTES_EXCEEDED');
              assert.equal(small.state.sessions[0].destroyCalls, 1);
            });
            test('BMP UTF8 and exact byte boundaries are enforced', async () => {
              assert.equal((await fixture({chunks: ['é', '中']}).bridge.infer(
                request({maxOutputBytes: 5}))).text, 'é中');
              failure(await fixture({chunks: ['é', '中']}).bridge.infer(
                request({maxOutputBytes: 4})), 'OUTPUT_BYTES_EXCEEDED');
            });
            test('malformed or nontext stream chunks are refused', async () => {
              for (const chunks of [[HIGH], [LOW], [HIGH, 'x'], [{type: 'tool-call'}], [null], [42]]) {
                const f = fixture({chunks});
                failure(await f.bridge.infer(request()), 'MALFORMED_OUTPUT');
                assert.equal(f.state.sessions[0].destroyCalls, 1);
              }
            });
            test('oversized output is rejected before final token measurement', async () => {
              const f = fixture({chunks: ['x'.repeat(10000)]});
              failure(await f.bridge.infer(request({maxOutputBytes: 16})), 'OUTPUT_BYTES_EXCEEDED');
              assert.equal(f.state.measurements.length, 1);
              assert.equal(f.state.sessions[0].destroyCalls, 1);
            });
            test('invalid request budgets and malformed prompts never create sessions', async () => {
              for (const changes of [{id: ''}, {prompt: ''}, {prompt: HIGH}, {maxTokens: 0},
                {maxOutputBytes: 0}, {timeoutMillis: 0}, {timeoutMillis: Infinity},
                {allowDownload: 'yes'}, {maxTokens: 1.5}]) {
                const f = fixture();
                failure(await f.bridge.infer(request(changes)), 'INVALID_REQUEST');
                assert.equal(f.state.creates, 0);
              }
            });
            test('prompt byte ceiling is checked before native work', async () => {
              const f = fixture();
              failure(await f.bridge.infer(request({prompt: 'é'.repeat(524289)})), 'INPUT_BYTES_EXCEEDED');
              assert.equal(f.state.availabilityCalls, 0);
              assert.equal(f.state.creates, 0);
            });
            test('context admission reserves the requested output budget', async () => {
              const f = fixture({window: 20, usage: 3, inputTokens: 10});
              failure(await f.bridge.infer(request({maxTokens: 8})), 'CONTEXT_BUDGET_EXCEEDED');
              assert.equal(f.state.prompts.length, 0);
              assert.equal(f.state.sessions[0].destroyCalls, 1);
              assert.equal((await fixture({window: 21, usage: 3, inputTokens: 10}).bridge.infer(
                request({maxTokens: 8}))).ok, true);
            });
            test('context overflow aborts instead of dropping conversation history', async () => {
              const f = fixture({overflow: true});
              failure(await f.bridge.infer(request()), 'CONTEXT_OVERFLOW');
              assert.equal(f.state.createOptions[0].signal.aborted, true);
              assert.equal(f.state.sessions[0].destroyCalls, 1);
            });
            test('output token excess is a refusal without truncation', async () => {
              const f = fixture({chunks: ['complete reply'], outputTokens: 9});
              failure(await f.bridge.infer(request({maxTokens: 8})), 'OUTPUT_TOKENS_EXCEEDED');
              assert.equal(f.state.measurements[1], 'complete reply');
              assert.equal(f.state.sessions[0].destroyCalls, 1);
            });
            test('invalid context measurements and missing measurement API fail closed', async () => {
              const invalidInput = fixture({inputTokens: NaN});
              failure(await invalidInput.bridge.infer(request()), 'INVALID_CONTEXT_MEASUREMENT');
              const invalidOutput = fixture({outputTokens: -1});
              failure(await invalidOutput.bridge.infer(request()), 'INVALID_CONTEXT_MEASUREMENT');
              const missing = fixture({create: (options, create) => {
                const session = create(); session.measureContextUsage = undefined; return session;
              }});
              failure(await missing.bridge.infer(request()), 'UNSUPPORTED_SESSION');
              assert.equal(missing.state.sessions[0].destroyCalls, 1);
            });
            test('each successful request has its own destroyed session', async () => {
              const f = fixture();
              assert.equal((await f.bridge.infer(request())).ok, true);
              assert.equal((await f.bridge.infer(request({id: 'r2'}))).ok, true);
              assert.equal(f.state.sessions.length, 2);
              assert.notEqual(f.state.sessions[0], f.state.sessions[1]);
              assert.deepEqual(f.state.sessions.map(s => s.destroyCalls), [1, 1]);
              assert.equal(f.timerCount(), 0);
            });
            test('cancel affects only the matching active request and releases it', async () => {
              let calls = 0;
              const f = fixture({stream: () => ++calls === 1 ? new ReadableStream({}) :
                new ReadableStream({start(c) { c.enqueue('next'); c.close(); }})});
              const pending = f.bridge.infer(request());
              await tick();
              assert.equal(f.bridge.cancel('other').cancelled, false);
              assert.equal(f.bridge.cancel('r1').cancelled, true);
              failure(await pending, 'CANCELLED');
              assert.equal(f.state.sessions[0].destroyCalls, 1);
              assert.equal(f.state.createOptions[0].signal.aborted, true);
              assert.equal((await f.bridge.infer(request({id: 'r2'}))).text, 'next');
              assert.equal(f.bridge.cancel('r1').cancelled, false);
            });
            test('simultaneous requests are refused without stealing ownership', async () => {
              const f = fixture({stall: true});
              const pending = f.bridge.infer(request());
              await tick();
              failure(await f.bridge.infer(request({id: 'r2'})), 'BUSY');
              failure(await f.bridge.prepare(false), 'BUSY');
              assert.equal(f.state.creates, 1);
              f.bridge.cancel('r1');
              failure(await pending, 'CANCELLED');
            });
            test('deadline covers native availability and late success cannot create', async () => {
              const gate = deferred();
              const f = fixture({availability: () => gate.promise});
              const pending = f.bridge.infer(request({timeoutMillis: 10}));
              f.advance(10);
              failure(await pending, 'TIMEOUT');
              gate.resolve('available'); await tick();
              assert.equal(f.state.creates, 0);
              assert.equal(f.timerCount(), 0);
            });
            test('late native creation after timeout is destroyed and never reused', async () => {
              const gate = deferred();
              let creates = 0;
              const f = fixture({create: (options, create) => ++creates === 1 ? gate.promise : create()});
              const pending = f.bridge.infer(request({timeoutMillis: 10}));
              await tick();
              assert.equal(f.state.creates, 1);
              f.advance(10);
              failure(await pending, 'TIMEOUT');
              const late = f.session(); gate.resolve(late); await tick();
              assert.equal(late.destroyCalls, 1);
              assert.equal((await f.bridge.infer(request({id: 'r2'}))).text, 'ok');
              assert.equal(f.state.sessions[1].destroyCalls, 1);
            });
            test('late native creation after cancellation is destroyed', async () => {
              const gate = deferred();
              const f = fixture({create: () => gate.promise});
              const pending = f.bridge.infer(request()); await tick();
              assert.equal(f.bridge.cancel('r1').cancelled, true);
              failure(await pending, 'CANCELLED');
              const late = f.session(); gate.resolve(late); await tick();
              assert.equal(late.destroyCalls, 1);
            });
            test('timeout stops a native stream that ignores AbortSignal', async () => {
              const f = fixture({stall: true});
              const pending = f.bridge.infer(request({timeoutMillis: 10})); await tick();
              f.advance(10);
              failure(await pending, 'TIMEOUT');
              assert.equal(f.state.sessions[0].destroyCalls, 1);
              assert.equal(f.state.createOptions[0].signal.aborted, true);
            });
            test('timeout covers final response measurement', async () => {
              const gate = deferred();
              const f = fixture({measure: (text, call) => call === 1 ? 4 : gate.promise});
              const pending = f.bridge.infer(request({timeoutMillis: 10})); await tick();
              f.advance(10);
              failure(await pending, 'TIMEOUT');
              gate.resolve(2); await tick();
              assert.equal(f.state.sessions[0].destroyCalls, 1);
            });
            test('bounded prepare timeout destroys a late warmup session', async () => {
              const gate = deferred();
              const f = fixture({create: () => gate.promise});
              const pending = f.bridge.prepare(false); await tick();
              f.advance(600000);
              failure(await pending, 'TIMEOUT');
              const late = f.session(); gate.resolve(late); await tick();
              assert.equal(late.destroyCalls, 1);
            });
            test('reinstall preserves active cancellation and completed reuse', async () => {
              const f = fixture({stall: true});
              const original = f.bridge;
              const pending = original.infer(request()); await tick();
              f.reinstall();
              assert.equal(f.context.chromeLlm, original);
              assert.equal(f.context.chromeLlm.cancel('r1').cancelled, true);
              failure(await pending, 'CANCELLED');
              assert.equal((await original.availability()).availability, 'available');
            });
            test('inference refuses missing model and never downloads implicitly', async () => {
              const f = fixture({availability: 'downloadable', activation: true});
              failure(await f.bridge.infer(request()), 'MODEL_NOT_READY');
              assert.equal(f.state.creates, 0);
              const activated = fixture({availability: 'downloadable', activation: true});
              assert.equal((await activated.bridge.infer(request({allowDownload: true}))).ok, true);
            });
            test('native rejection retires resources and leaves the bridge usable', async () => {
              let count = 0;
              const f = fixture({stream: () => {
                if (++count === 1) throw new TypeError('native provider failure');
                return new ReadableStream({start(c) { c.enqueue('recovered'); c.close(); }});
              }});
              failure(await f.bridge.infer(request()), 'PROMPT_API_ERROR');
              assert.equal(f.state.sessions[0].destroyCalls, 1);
              assert.equal((await f.bridge.infer(request({id: 'r2'}))).text, 'recovered');
            });
            test('failed session destruction refuses success and further inference', async () => {
              const f = fixture({destroyFails: true});
              failure(await f.bridge.infer(request()), 'SESSION_CLEANUP_FAILED');
              failure(await f.bridge.infer(request({id: 'r2'})), 'SESSION_CLEANUP_FAILED');
              assert.equal(f.state.creates, 1, 'uncertain native state must not be reused');
              assert.equal(f.state.sessions[0].destroyCalls, 1);
            });
            test('failed warmup destruction cannot report readiness', async () => {
              const f = fixture({destroyFails: true});
              const result = await f.bridge.prepare(false);
              failure(result, 'SESSION_CLEANUP_FAILED');
              assert.equal(result.ready, false);
            });
            test('output allocation ceiling cannot be enlarged through a direct bridge call', async () => {
              const f = fixture();
              failure(await f.bridge.infer(request({maxOutputBytes: 1048577})), 'INVALID_REQUEST');
              assert.equal(f.state.creates, 0);
            });

            (async () => {
              let failures = 0, executed = 0;
              for (const {name, run} of cases) {
                if (!filter.test(name)) continue;
                executed++;
                try { await run(); console.log('PASS ' + name); }
                catch (error) { failures++; console.error('FAIL ' + name + ': ' + error.message); }
              }
              assert.ok(executed > 0, 'at least one behavioral contract must run');
              console.log('Browser script contracts: ' + (executed - failures) + '/' + executed + ' passed');
              if (failures) process.exitCode = 1;
            })().catch(error => { console.error(error); process.exitCode = 1; });
            """;
}
