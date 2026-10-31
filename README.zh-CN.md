# ChromeLLM — Java 21 / JNI 独立模型推理

当前迁移草案已从产品源码树中删除 ChromeML、Dawn、Node.js、PowerShell、Chromium API 头文件及 Windows runner。**不需要启动 Chrome 浏览器。** 历史内容保存在 Git 历史中。

**模型注意事项：** 需要单独取得有合法许可、与 llama.cpp 兼容的 GGUF 模型。Chrome Gemini Nano 的 weights.bin 不是 GGUF，不可直接用于此 JNI 后端。不要从 Chrome profile 中复制或分发受限制的模型或 DLL。

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

参考：[standalone/README.md](standalone/README.md)、[当前架构](docs/START_HERE.md)、[迁移记录](docs/BROWSER_REMOVAL_MIGRATION.md)。[ChromeLLM PR #19](https://github.com/hsoliwal/ChromeLLM/pull/19) 尚为草案。
