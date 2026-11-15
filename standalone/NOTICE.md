# Optional installed-Chrome AI provider notices

Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.

The new `ChromeAiBackend`, `ChromeDevTools`, `ChromeProcess`, and `ChromeAiScript` Java sources and their new contract tests are distributed under the [Apache License, Version 2.0](../LICENSES/Apache-2.0.txt), as marked in their source headers. Existing files retain their original license notices. The repository's historical Chrome2api attribution and MIT license remain applicable to the corresponding work.

`ChromeDevTools` adapts the Synexia project's Java CDP transport, with bounded request admission, explicit ownership, concurrent cancellation, strict loopback endpoints, and end-to-end deadlines.

The browser-side lifecycle in `ChromeAiScript` adapts availability/creation option consistency, activation handling, download monitoring, session cleanup, and delta streaming patterns from Google's [web-ai-demos](https://github.com/GoogleChromeLabs/web-ai-demos/tree/2edcaedca4a13913598b6365d3c41b0f96e8d09d/easy-language-model) project at revision `2edcaedca4a13913598b6365d3c41b0f96e8d09d`, specifically `src/create-session.js`, `src/user-activation.js`, and `src/easy-language-model.js`.

Copyright 2026 Google LLC. Those source files are licensed under the Apache License, Version 2.0; the license is reproduced in this repository. The adapted implementation adds request identity, bounded input/output, isolated sessions, deadline/cancellation behavior, measured response admission, and cleanup failure handling.

Chrome is a separately installed product. This repository does not distribute Chrome binaries, model weights, or a Chrome model runtime library. The selected installation's licenses, availability, and policies govern its use.
