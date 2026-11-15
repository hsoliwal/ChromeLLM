# M3 Chrome AI Agent — optional on-device build planner extension

**Status:** source-only optional ChromeLLM extension; explicit operator installation required. No installation or activation has occurred. This package provides a Chrome Manifest V3 extension UI and a Java 21 Native Messaging host. It does **not** download binaries, execute Maven, create PRs, install extensions, or expose a general shell.

## What it demonstrates

1. An extension page checks Chrome's current built-in `LanguageModel` availability and, only after a user click, creates a model session. Chrome manages its own model acquisition; this package never accesses Chrome's model bytes.
2. Chrome's `nativeMessaging` bridge delivers a narrow JSON request to a Java 21 host.
3. The Java host performs read-only allowlisted repository checks and reports SHA-256 hashes of selected build contract files.
4. The browser AI generates a proposed build plan from the inspection result, shown to the user as **untrusted text**. It cannot launch Maven or edit source.

## Files

- `docs/CONTRACT.md`: behavior, security and failure-mode contract.
- `extension/`: unpacked Chrome extension (Manifest V3).
- `host/src/main/java/io/synexia/chromellm/webmcp/`: Java 21 native host with framed JSON transport, strict parsing and read-only inventory.
- `host/config.example.properties`: explicit, allowlisted filesystem roots.
- `host/tests/test_host.py`: protocol and security regression suite.
- `extension/tests/popup.test.js`: mocked Chrome UI flow tests; not a real-browser qualification.

## Build and verify locally

Requires JDK 21 and Python 3 for tests. No Maven or browser is required for host verification.

```sh
cd host
mkdir -p target/classes
javac -encoding UTF-8 -Xlint:all -Werror -d target/classes src/main/java/io/synexia/chromellm/webmcp/*.java
python3 tests/test_host.py
cd ../extension && node --test tests/popup.test.js && cd ../host
jar --create --file target/m3-chrome-ai-host.jar --main-class io.synexia.chromellm.webmcp.AgentHost -C target/classes .
```

The regression suite constructs its own temporary approved repository roots and invokes the Java host over a Native Messaging framing stream. No network or root writes are required.

## Opt-in Chrome installation (Linux)

1. Build the Java host (above). Copy the **whole package** to a stable user-owned path.
2. Copy `host/config.example.properties` to an operator-owned configuration path and set `repo.ChromeLLM`, `repo.dawn`, `repo.synexia-llmcpp`, `repo.maven` to actual, approved checkout paths (nonexistent paths remain unavailable).
3. Create an executable launcher in an approved directory that calls `java -cp /absolute/path/to/host/target/classes AgentHost /absolute/path/to/config.properties` with stdout reserved for the native protocol.
4. In `chrome://extensions`, enable Developer mode and choose **Load unpacked** for `extension/`. Copy the resulting extension ID.
5. In the same properties file set `agent.allowed_origin=chrome-extension://YOUR_EXTENSION_ID/`. Register the `io.synexia.m3_chrome_ai_agent` Native Messaging host with a manifest pointing to the absolute launcher path and `allowed_origins` containing only `chrome-extension://YOUR_EXTENSION_ID/`.
6. Use the extension popup to inspect the repo and request a proposal from the Chrome-managed model. After reviewing the advisory, press **Copy candidate**. Save the text explicitly to an approved file and pass that file with Maven `-Dm3.smart.candidateFile=/approved/candidate.txt`; model text remains untrusted and never authorizes or executes commands. This is an opt-in model download, subject to Chrome's Prompt API eligibility checks.

See Chrome's native messaging documentation for platform-specific manifest locations. **Do not put generic shell commands or Git credentials into the native host.** A Windows installer needs a Windows executable launcher (e.g., package this Java application with `jpackage` on Windows) and `HKCU` NativeMessagingHosts registration; this package does not silently install or modify Windows registry keys.

Chrome APIs used: https://developer.chrome.com/docs/ai/prompt-api and https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging .

The launcher forwards Chrome's extension-origin launch argument and optional Windows window handle. The Java host matches it to `agent.allowed_origin`; a mismatch exits before reading native frames. For direct offline tests it also accepts the fixed config path without a Chrome origin. See [Chrome origin and handoff contract](docs/CONTRACT.md).

## Windows packaging boundary (not yet qualified)

The native host manifest on Windows must identify an executable, not a `.ps1` file.
An operator can build the JAR with Java 21, then use Windows `jpackage --type
app-image --win-console` with the approved configuration path as the launcher’s
fixed argument; Chrome appends the extension origin and optional parent-window
argument. Register the host manifest in the user's HKCU NativeMessagingHosts key
only after reviewing the installed executable and extension ID. The installer,
Windows launcher, Chrome permissions, GPU/CPU model eligibility and live browser
transport have **not** been tested here. No registry writes or system installs are
performed by these sources.
