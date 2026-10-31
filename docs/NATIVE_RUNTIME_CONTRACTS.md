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

## Browser-free llama.cpp JNI boundary

The standalone native entry is **standalone/native/llama_jni.cpp**, not the former Node HTTP server or Windows ChromeML runner. The bridge retains model handles in a checked native registry and creates a bounded per-inference context and greedy sampler. The Java backend takes explicit paths to a separately built, license-reviewed llama.cpp native library and compatible GGUF.

Generate and close operations are synchronized on the Java backend instance. Native shared ownership prevents a freed model during a call. UTF-8 input/output, context sizing, maximum generated bytes and cooperative deadlines are checked. CPU abort checks do not guarantee instantaneous cancellation during GPU kernels.

The old /v1/chat/completions endpoint was provided by a removed Node server. **The standalone module does not currently provide an HTTP listener or full OpenAI-compatible server.** Build a separate authenticated adapter and prove it before claiming protocol compatibility.

Passing the synthetic JNI fixture does not qualify real GGUF inference, model rights, GPU acceleration or platform support.
