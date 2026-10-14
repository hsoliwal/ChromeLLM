<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# ChromeLLM → M3JDK21 structured Java 21 chat API

**Status:** independent Apache-2.0 Java client module implemented and
locally JDK21-compiled/JMOD-tested; actual Chrome native/model execution
is caller-dependent and was **not** part of that proof.

## Existing server versus new module

This fork continues to own its existing Chrome2api-compatible local server:

```text
local caller
 -> com.synexia.m3.chrome.chat.ChromeChat (optional Java 21 module)
 -> http://127.0.0.1:11435/v1/chat/completions
 -> ChromeLLM's current runtime/chromeml_api_server.cjs
 -> runtime/ChromeMLBareRunner.exe
 -> external Chrome-specific native DLLs + user-licensed weights
```

The Java module is **not a replacement or modification of Chrome2api's
server**. It is a strict, non-streaming request/response client for a
separately launched compatible server. ChromeLLM's other features (diffusion,
images, video, official Chrome built-in AI APIs, JNI/OpenCL, streaming)
do not automatically become part of this first, deliberately bounded client.

Canonical first-party Java/recipe owner:
[hsoliwal/com.synexia/synexia-m3-jmods](https://github.com/hsoliwal/com.synexia/tree/develop/synexia-m3-jmods)
and the `M3HashPinnedJavaSnapshotRecipe` with crate
`m3-chrome-tornado-jmods-20261014`.
Receiver:
[hsoliwal/M3jdk21/m3/optional-jmods](https://github.com/hsoliwal/M3jdk21/tree/master/m3/optional-jmods)
(after PR acceptance; branches may have the candidate earlier).

## Public Java contract

```java
import com.synexia.m3.chrome.chat.ChromeChat;
import java.util.List;

try (ChromeChat api = new ChromeChat(11435)) {
    ChromeChat.Completion answer = api.complete(
            "chrome-gemini-nano",
            List.of(new ChromeChat.Message("system", "Be concise."),
                    new ChromeChat.Message("user", "Hello")),
            128);
    System.out.println(answer.content());
}
```

`new ChromeChat(port)` targets only literal IPv4 loopback
`127.0.0.1` and does not accept arbitrary URL/hostname or model path.
It disables proxies and redirects. Requests allow 1–64 immutable
messages with the accepted roles `system`, `user`, `assistant`,
`tool`; response bytes and content length are bounded. Invalid
JSON, duplicate JSON keys, invalid Unicode and non-200 responses
fail closed. It never silently produces a successful completion from
an error response. The Java client does not inspect Chrome profiles,
download weights, run executables or invoke cloud APIs.

The API is optional; it does **not** modify `java.net.http`,
`java.lang`, HotSpot or JNI. JMODs are built with Java 21 from
independently licensed code and may be incorporated in a developer
`jlink` image after module and runtime checks.

## Maven / OpenRewrite / test contract

Build the canonical `com.synexia.m3.chrome.chat` JMOD:

```sh
java --source 21 synexia-m3-jmods/tools/M3JmodBuild.java verify synexia-m3-jmods
```

For the independent Maven JPMS JAR, use
`synexia-m3-jmods/modules/com.synexia.m3.chrome.chat/pom.xml`.
For a pre-admitted candidate port to M3JDK21, use the canonical
OpenRewrite recipe `com.synexia.rewrite.M3ChromeTornadoJmodApis20261014`,
which pins the Java source and module descriptor to exact SHA-256.
A JMOD/image build is separate from OpenRewrite LST replay and
from actual Chrome native inference.

The local independent Java 21 oracle includes an in-process HTTP server
with a successful UTF-8 completion and an HTTP 500 rejection, plus JSON
and request validation; it runs without model weights or DLLs.
Additional tests against **a licensed local ChromeML runtime** are
pending and must not be implied by this simulated HTTP proof.

## License / provenance

This JMOD client is first-party Apache-2.0 and copies no Chrome,
Dawn, Gemini model, or Google-owned runtime source. Original ChromeLLM
and upstream component licenses/NOTICE remain unchanged. Model files
must remain outside Git and are never distributed by this integration.
