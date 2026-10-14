# Native bridges and Windows HTTP runtime contracts

[Projects](ARCHITECTURE_AND_PROJECTS.md) · [Java packages](JAVA_PACKAGE_CONTRACTS.md) · [Media lifecycle](MEDIA_PIPELINE_CONTRACTS.md) · [Coverage](DOCUMENTATION_COVERAGE.md)

Pinned source: `26473e370c6d7cedace4a0b21e8ad56a35ff2dfc`. This is a source-level guide, not an ABI compatibility certificate or test receipt.

## Library selection and ownership

| Property | Default | Consumer |
| --- | --- | --- |
| `chromellm.diffusion.library` | `chromellm_diffusion` | JNA `NativeDiffusionLibrary` |
| `chromellm.diffusion.jni.library` | `chromellm_diffusion_jni` | JNI diffusion class initializer |
| `chromellm.gpu.library` | `chromellm_gpu` | JNA `NativeGpuLibrary` |
| `chromellm.gpu.jni.library` | `chromellm_gpu_jni` | JNI OpenCL class initializer |
| `chromellm.opencv.jni.library` | `chromellm_opencv_jni` | JNI OpenCV class initializer |
| `chromellm.ffmpeg.jni.library` | `chromellm_ffmpeg_jni` | JNI FFmpeg class initializer |
| `chromellm.ffmpeg` / `chromellm.ffprobe` | `ffmpeg` / `ffprobe` | External media processes |

These names select libraries/executables; they do not install them. JNI search-path settings and OS dependent-library lookup must both be satisfied. [FORK_DEVELOPMENT.md](FORK_DEVELOPMENT.md) gives the Linux integration invocation. Explicit backend selection surfaces load errors; AUTO factories catch initialization failures and can hide the first cause by selecting fallback. Use explicit selection while diagnosing native setup.

## Diffusion C ABI

[`diffusion_native.h`](../native/diffusion_native.h) exposes `diffusion_create`, `diffusion_generate`, `diffusion_destroy`, and `diffusion_last_error`. The [implementation](../native/diffusion_native.cpp) stores dimensions and `std::mt19937_64` state in a handle. Create returns null for invalid geometry/channels or caught allocation failure. Generate requires a live handle, output pointer, positive steps and exactly matching output length; return 1 indicates basic invalid arguments and 2 output-length mismatch. Error text is thread-local and cleared by create/generate; copy it before another operation overwrites it.

The caller owns the output buffer and handle. Destroy exactly once; do not reuse a freed pointer or use a handle concurrently. Repeated generation on the same C handle advances its RNG; Java wrappers create a new handle per call. Java and native PRNGs differ, so seed equality across backends is not pixel parity. The native boundary does not duplicate every Java record validation and does not comprehensively catch allocation exceptions during generation; do not expose raw ABI calls as an untrusted-input safety layer.

[`diffusion_jni.cpp`](../native/diffusion_jni.cpp) copies Java conditioning arrays to native vectors, creates a handle, generates, destroys it and returns a new float array. Null create/nonzero generation is translated to `IllegalStateException`. The JNA wrapper closes its handle in Java `finally`; this differs from a claim that all possible native exception paths have been proven leak-free.

## OpenCL C ABI and per-call allocations

[`gpu_opencl.h`](../native/gpu_opencl.h) declares create/destroy, device metadata, processing, blending, generation and thread-local error access. [`gpu_opencl.cpp`](../native/gpu_opencl.cpp) loads the OS OpenCL library and required symbols at runtime. Create picks the first discovered GPU; only when `allow_cpu_fallback` is nonzero may it search CPU/all devices. It creates a context, command queue, program and three mutable kernels. A kernel-build error includes the OpenCL build log. The context destructor releases kernels, program, queue and context.

Operations allocate OpenCL input/output buffers per call, transfer data, set kernel arguments, enqueue one-dimensional work, perform blocking readback/finish and release temporary buffers. This is synchronous processing with copied buffers. There is no lock around kernel argument mutation or destroy; one context must not be shared concurrently. Invalid basic arguments return −1, kernel argument failure can return −2, and other errors retain OpenCL codes. The raw C ABI has no buffer-length parameters; the caller must provide sufficiently large RGBA arrays. The [JNI wrapper](../native/gpu_jni.cpp) validates array lengths before invoking it.

`PixelOperation.nativeCode()` and `PixelGenerator.nativeCode()` define the explicit integer protocol. The C kernel defaults unknown processing operations to copy and unknown generator modes to checkerboard; Java enums prevent those unknown modes through the normal wrapper. Noise hashing differs from the Java implementation, OpenCL rounding uses saturating round-to-even, and the OpenCL checkerboard cell limit is 512 while Java has no matching upper clamp. A backend fallback is therefore not a byte-for-byte equivalent implementation promise.

## OpenCV operation boundaries

[`opencv_jni.cpp`](../native/opencv_jni.cpp) validates RGBA/mask lengths, copies Java arrays and uses local `cv::Mat`/vector lifetimes. Implemented algorithms are:

- Inpaint: binarize every nonzero mask value, RGBA→BGR, Telea inpainting with minimum radius 0.1, then restore source alpha
- Seamless clone: clip object placement to background, derive binary mask from object alpha, use `NORMAL_CLONE`, restore background alpha; return background if no visible/masked content
- GrabCut: clamp rectangle to image, reject no intersection, run rectangle-initialized GrabCut with at least one iteration, produce foreground/probable-foreground mask
- Interpolate: clamp position, return endpoint bytes at 0/1, otherwise calculate forward/backward Farneback flow, remap with reflective border and blend warped frames

Caught native exceptions are translated to Java state exceptions. These operations differ substantially from Java fallbacks; see [vision package](JAVA_PACKAGE_CONTRACTS.md#vision). Optional target detection and one opt-in smoke-style integration test do not establish every image/geometry/error case.

## FFmpeg native decoder

[`ffmpeg_decoder_jni.cpp`](../native/ffmpeg_decoder_jni.cpp) owns format/codec/packet/frame/scaler in a decoder context. Opening uses `avformat_open_input`, stream info, best video stream and codec initialization; failure paths delete the context. Successful close frees scaler, frame, packet, codec and format resources. The decoder negotiates a frame rate with fallback to 30, decodes sequentially, drains at EOF and converts to fixed-size RGBA via cached swscale context. It does not expose audio frames or an encoder.

JNI calls require a live handle and exactly sized output byte array. Native failures commonly throw `IllegalStateException`; invalid path/output geometry may throw `IllegalArgumentException`. Backward seek rescales milliseconds into stream time base, calls `av_seek_frame`, flushes codec buffers and resets EOF flags. The Java adapter is closeable and invalidates its handle, but concurrent seek/read/close is not synchronized. Read the [stream guide](MEDIA_PIPELINE_CONTRACTS.md) for backend differences and partial-output behavior.

## HTTP server configuration and protocol

[`runtime/chromeml_api_server.cjs`](../runtime/chromeml_api_server.cjs) is a top-level executable CommonJS module, not an exported library. Its supported routes are GET `/v1/models`, POST `/v1/chat/completions`, and OPTIONS; other routes return 404. The [existing API guide](OPENAI_COMPAT_API.md) describes request examples and compatibility limits.

| Environment name | Source default | Operational meaning |
| --- | --- | --- |
| `CHROMEML_API_HOST` | `127.0.0.1` | Bind address |
| `CHROMEML_API_PORT` | `11435` | Parsed integer listen port |
| `CHROMEML_API_TIMEOUT_MS` | `120000` | Runner timer after the queued job starts; not total request/queue wait |
| `CHROMEML_API_MAX_BODY_BYTES` | `10485760` | Request body and decoded inline image size limit |
| `CHROMEML_API_DEBUG_PROMPT` | disabled, enabled only by `1` | Logs prompts and runner arguments to stderr |

These numeric environment values are parsed, not comprehensively range-validated. Configure positive sensible values. The server permits CORS `*` and does not enforce API-key authentication. A client Authorization header does not protect access. Keep it on loopback and treat it as a trusted local process with access to the service account's files; local media paths are existence-checked but not confined to the repository.

Requests require nonempty messages and `n=1` when n is supplied. Content becomes a role-labelled text prompt ending in `assistant:`. Images may be local paths/file URLs or supported base64 image data URLs; remote HTTP(S) media URLs are rejected. Inline images are written under `runtime/tmp/media`; audio uses local paths and at most one file. Positive-integer options include token limits/context/top-k/audio sample rate; temperature must be finite. This is an adapter-specific contract, not full OpenAI API compatibility.

## Queue, subprocess and error lifecycle

Both streaming/nonstreaming requests share a single promise queue. A failed job is caught on the queue tail so later jobs can proceed. The queue has no explicit length bound. Each job spawns `powershell.exe` with the launcher, prompt/media/sampling arguments and repository working directory. The runner timeout starts there; expiry calls `child.kill()`. There is no explicit process-tree shutdown or client-disconnect cancellation protocol.

The server accumulates stdout/stderr and parses runner text markers. Nonzero process exit, timeout or missing `full_text` becomes an error. Parsed `complete_status` is returned internally but is not used to choose the HTTP response's success/finish reason; the adapter always reports `stop` on its success path and null token usage. Do not treat those fields as full model-side accounting.

Streaming sends SSE headers and an assistant-role chunk before queue execution, then token chunks, a final stop chunk and `[DONE]`. After headers are sent, a job failure is represented by an SSE error object followed by `[DONE]`, rather than changing the HTTP status. Nonstreaming validation errors use 400/413; runner/internal errors use 500. Oversize request handling destroys the incoming request, so clients may observe a closed connection instead of a fully delivered error body.

Temporary media cleanup is in a `finally` around job execution. If prompt construction or subsequent option validation fails before entering that block, already-written inline images can remain. Cleanup failures are ignored except debug logging. This is the observed boundary, not a promise of complete cleanup. Avoid sensitive media/debug logging and inspect the temporary directory after failed local requests.

## Windows runner boundary and remaining work

[运行裸Runner.ps1](../运行裸Runner.ps1) supplies GPU/chat-input/no-cache/fast flags, fixed repository-relative DLL/model locations, default maximum output 128 and audio target rate 16000. It supports repeated image flags and passes optional context/sampling arguments. [scripts/build_runner.ps1](../scripts/build_runner.ps1) builds the C++ runner separately from CMake. Its printed “Built” line is not an independently observed build result.

The large runner's detailed ChromeML/Dawn ABI, callback lifecycle and compatibility with bundled binaries remain a separately identified review gap. This guide fully inspects the HTTP/launcher boundary and media native bodies, but does not infer every runner implementation contract from those adapters. Generated/reference ABI headers and opaque binary interiors are inventoried, not re-documented as first-party APIs. See [coverage](DOCUMENTATION_COVERAGE.md) and preserve existing notices before any redistribution.
