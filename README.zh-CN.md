# ChromeLLM — Java 21 / JNI 独立模型推理

当前独立运行时支持本地 llama.cpp CLI/JNI，以及显式选择的 Gemini 4 Hydrogen API。Java 模块仅依赖 `java.base` 和 `java.net.http`。本地 JNI 推理可通过 llama.cpp 已有的 WebGPU 后端使用独立构建的 Dawn。**不需要启动 Chrome 浏览器。** 历史内容保存在 Git 历史中。

## Hydrogen 与 WebGPU

- Hydrogen：在进程环境中设置 `GEMINI_API_KEY`，使用 `--backend gemini --prompt-file /path/to/request.txt`。默认模型为 `gemini-4-hydrogen`，默认思考级别为 `high`；提示内容会发送到 Google API。
- 本地 WebGPU：使用 `CHROMELLM_LLAMA_SOURCE_DIR`、`CHROMELLM_WEBGPU=ON` 和已安装的 Dawn CMake 包构建 JNI 库，然后选择 `--backend jni --compute-backend webgpu --gpu-layers 32`。
- 本地与云端后端不会自动互相切换。显式请求 WebGPU 时，缺少相应设备会报错。

完整命令和限制见 [standalone/README.md](standalone/README.md)。

**模型注意事项：** 本地推理需要单独取得有合法许可、与 llama.cpp 兼容的 GGUF 模型。Chrome Gemini Nano 的 weights.bin 不是 GGUF，不可直接用于此 JNI 后端。不要从 Chrome profile 中复制或分发受限制的模型或 DLL。

## 进程内 JNI 使用方法

```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B standalone/native/build \
 -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
 -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
 -DLLAMA_LIBRARY=/path/to/libllama.so
cmake --build standalone/native/build
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
 --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
 --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
 --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
 --threads 4 --context-tokens 4096 --gpu-layers 0 --max-tokens 256
```


Windows 和 macOS 分别使用 DLL 和 dylib 路径；还需正确配置原生依赖加载路径。CLI 适配器可选，但 JNI 是所需的进程内执行路径。

## 验证边界

Java/JNI 合成测试验证 Unicode、超时和资源清理，但不包含真实模型。真实 GGUF 推理、GPU 和跨平台运行仍待验证。

参考：[standalone/README.md](standalone/README.md)、[当前架构](docs/START_HERE.md)、[迁移记录](https://github.com/hsoliwal/com.synexia/blob/develop/forks/ChromeLLM/docs/BROWSER_REMOVAL_MIGRATION.md)。
