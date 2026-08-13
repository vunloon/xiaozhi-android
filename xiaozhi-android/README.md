# xiaozhi-android

Android voice-chat client for [xiaozhi](https://xiaozhi.me). Implements
**Plan 1** of [../../plans/plan-1-voice-client.md](../../plans/plan-1-voice-client.md)
— a full push-to-talk voice client that connects to the official xiaozhi
cloud (`wss://api.tenclass.net/xiaozhi/v1/`) for STT/LLM/TTS. Tools come
from a **remote** MCP server (e.g. the existing
[`../xiaozhi-wss`](../../xiaozhi-wss) Python host) registered to the same
agent account — this app hosts no tools itself.

For tool hosting on the phone, see
[Plan 2](../../plans/plan-2-mcp-tools.md) (not yet implemented).

## What's in this build

| Component | Status |
|---|---|
| Identity (EfuseStore, MAC/serial/HMAC generation, EncryptedSharedPreferences) | ✅ |
| Activation v2 (OTA POST + HMAC challenge loop, 5 s × 60 retries) | ✅ |
| WebSocket device session (OkHttp, 4 headers, `hello` handshake, auto-reconnect) | ✅ |
| Opus codec (libopus 1.5.2 built from source + JNI wrapper) | ✅ |
| Audio capture (16 kHz mono PCM → Opus 20 ms frames) | ✅ |
| Audio playback (Opus → 24 kHz mono, low-latency AudioTrack) | ✅ |
| Push-to-talk UI (Jetpack Compose, Material 3) | ✅ |
| Tool-activity drawer (surfaces `{type:"mcp"}` envelopes from your remote MCP) | ✅ |
| IoT plumbing (empty ThingManager, command handling hook) | ✅ |
| Foreground service (`FOREGROUND_SERVICE_MICROPHONE`) | ✅ |
| Wake word (sherpa-onnx) | ❌ — phase 4 stretch goal |
| Multimodal / camera | ❌ — out of Plan 1 scope |

## Requirements to build

| Tool | Version | Why |
|---|---|---|
| JDK | 17 | Android Gradle Plugin 8.5 |
| Android SDK | API 34 + Build-Tools 34.0.0 | `compileSdk = 34` |
| Android NDK | 26.3.11579264 | Builds the JNI wrapper |
| CMake | 3.22.1 | Builds the JNI wrapper |
| Gradle | 8.7 (wrapper included) | Build orchestration |

A prebuilt libopus.so for `arm64-v8a`, `armeabi-v7a`, and `x86_64` is already
shipped under `app/src/main/jniLibs/<abi>/` so you don't need to rebuild it.
To rebuild from source (e.g. to bump the Opus version), see
[`scripts/build-libopus.sh`](scripts/build-libopus.sh).

## Build

```bash
# Debug APK
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release APK (runs R8; unsigned — bring your own keystore to sign)
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk

# Unit tests
./gradlew :app:testDebugUnitTest
```

### Installing on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 8.0 (API 26) or newer required. Three ABIs are bundled:
`arm64-v8a` (modern phones), `armeabi-v7a` (older phones), `x86_64`
(emulators).

## How to use

1. **Open the app.** A 6-digit activation code appears (the first time only).
2. **Browse to <https://xiaozhi.me>** on any device, log in, and add a new
   device using the code. The Android device will detect activation within
   ~5 seconds.
3. Once activated, the chat screen appears. Tap **Reconnect** in the top bar
   if needed.
4. **Hold the mic button** to talk; release to send. The assistant's reply
   is played back as TTS and added to the transcript.
5. Open the **Tool activity drawer** (top-right icon) to see MCP tool calls
   being routed through your remote `xiaozhi-wss` MCP server (if any).

### Connecting your remote tools

Plan 1 hosts no tools itself. To make voice prompts trigger your existing
[`../xiaozhi-wss`](../../xiaozhi-wss) tools (Malaysia news, KLSE stocks,
YouTube Music), make sure:

1. Your `xiaozhi-wss` Python server is running somewhere reachable.
2. It connects to `wss://api.xiaozhi.me/mcp/?token=<JWT>` using a JWT from
   the **same xiaozhi account** you activated this Android device under.
3. The agent has the `omo` (or equivalent) MCP broker enabled so the cloud
   can route tool calls.

When you ask the device "what's Maybank's price?", the cloud LLM calls the
remote MCP server's `get_klse_stock_data` tool and TTS-reads the result back.
The Android app surfaces the call in the tool-activity drawer.

## Architecture

```
src/main/java/com/xiaozhi/client/
├── XiaozhiApp.kt                  # Application + manual DI container
├── MainActivity.kt                # Compose host
├── core/
│   ├── identity/                  # EfuseStore (EncryptedSharedPreferences), DeviceFingerprint
│   ├── activation/                # OtaClient (HTTP), ActivationManager (v2 loop), DTOs
│   └── protocol/                  # WebSocketProtocol, ProtocolMessage, SessionStore
├── audio/                         # OpusCodec (JNI), AudioCapture (mic), AudioPlayer (speaker)
├── state/                         # ChatStateMachine (IDLE/LISTENING/SPEAKING)
├── iot/                           # ThingManager (empty stub)
├── service/                       # VoiceService (foreground)
└── ui/
    ├── theme/                     # Material 3 dark palette
    ├── nav/                       # NavGraph
    ├── activation/                # ActivationScreen
    ├── chat/                      # ChatScreen + ChatViewModel
    └── components/                # PttButton, ToolActivityDrawer

src/main/cpp/                      # JNI bridge
├── CMakeLists.txt
├── opus_jni.cpp
└── opus/include/                  # vendored libopus headers

src/main/jniLibs/<abi>/            # prebuilt libopus.so (per ABI)
```

## Wire format notes

The device-session protocol is binary-text mixed:

- **Text frames** = JSON control messages. All messages carry a `type`
  discriminator and (after the server's first `hello`) a `session_id`.
  Snake_case wire keys are mapped to camelCase Kotlin properties via
  `@SerialName`.
- **Binary frames** = Opus packets. Client → server at 16 kHz mono / 20 ms.
  Server → client at 24 kHz mono / 20 ms.

Headers on the WebSocket upgrade:

```
Authorization: Bearer test-token
Protocol-Version: 1
Device-Id: <locally-administered MAC>
Client-Id: <UUIDv4>
```

## Security notes

- Device identity (`mac_address`, `serial_number`, `hmac_key`,
  `client_id`, `activation_status`) is stored in `EncryptedSharedPreferences`
  (AES256-GCM) at `xiaozhi_efuse.xml`. The HMAC key is what signs the
  activation challenge, so back it up if you care about preserving the
  device's identity across reinstalls.
- The app shows a **verification code** to the user during activation — this
  is the same UX as the ESP32 / py-xiaozhi clients.
- Debug builds disable TLS certificate verification (`TRUST_ALL_CERTS=true`)
  so you can intercept traffic with a proxy while developing. Release
  builds keep TLS verification on.

## Tested

- ✅ Unit tests: identity formatting (MAC/serial/HMAC) + JSON wire shapes
- ✅ `./gradlew :app:assembleDebug` — produces a 20.7 MB APK
- ✅ `./gradlew :app:assembleRelease` — produces a 14 MB APK (R8-minified)
- ✅ Native libs packaged for `arm64-v8a`, `armeabi-v7a`, `x86_64`
- ❌ Not yet tested on a physical device or emulator — needs a real
  activation run against the official xiaozhi backend before merging.

## License

Same license as the parent repository. Third-party code:
- **libopus** — BSD-3-Clause (© Xiph.Org Foundation)
- **Opus reference implementation** built from
  <https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz>
