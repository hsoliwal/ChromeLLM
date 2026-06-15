# Model Files

Place the Chrome Gemini Nano model weights here before running the bare runner:

```text
model\OptGuideOnDeviceModel\2025.8.8.1141\weights.bin
```

`weights.bin` is not committed to this repository because it is several GB and exceeds GitHub's normal file size limit.

On Windows, Chrome usually stores the downloaded model here:

```text
%LOCALAPPDATA%\Google\Chrome\User Data\OptGuideOnDeviceModel\2025.8.8.1141\weights.bin
```

Copy the whole version directory into this repository:

```powershell
robocopy "$env:LOCALAPPDATA\Google\Chrome\User Data\OptGuideOnDeviceModel\2025.8.8.1141" `
  ".\model\OptGuideOnDeviceModel\2025.8.8.1141" /E
```
