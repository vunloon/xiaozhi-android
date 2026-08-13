# Plan 1 — xiaozhi Voice Chat Client (remote-WSS tools)

Full Android xiaozhi voice client. Uses the existing xiaozhi cloud for STT/LLM/TTS.
Tools are **not** hosted by the app — they come from your **remote MCP server**
(e.g. the existing [`xiaozhi-wss`](../../xiaozhi-wss) Python server) registered to
the same agent account. The Android app surfaces tool activity in the UI but
implements no tools itself.

- **Endpoint**: `wss://api.tenclass.net/xiaozhi/v1/`
- **Auth**: `Bearer test-token` + `Device-Id` (MAC) + `Client-Id` (UUID) + activation v2 (HMAC challenge)
- **Audio**: 16 kHz mono Opus 20 ms frames in; 24 kHz mono Opus out
- **Effort**: ~9–10 working days

---

## Architecture

```
┌──────────────────────── Android app (Kotlin) ─────────────────────────────────┐
│                                                                                │
│  UI (Compose)              Audio I/O             Protocol         Identity      │
│  ────────────              ─────────             ─────────        ────────      │
│  Activation screen         AudioRecord 16k  ──┐  WebSocketProtocol  EfuseStore  │
│  Chat screen               → Opus encode    │  (OkHttp WSS to     (Encrypted   │
│  Transcript bubbles        → binary send    │   api.tenclass.net  SharedPrefs) │
│  PTT FAB                                    ├──  /xiaozhi/v1/)                 │
│  Tool-activity drawer      Opus decode  ←── │                                  │
│  Settings                  → AudioTrack 24k │  Activation: OtaClient +          │
│                                             │   HMAC v2 challenge               │
│                                             │                                  │
│                                             │  In-session msg handlers:         │
│                                             │   hello/listen/abort/tts/stt/llm  │
│                                             │   + iot (descriptors/commands)    │
│                                             │   + mcp envelope (NO tools         │
│                                             │      registered in Plan 1)         │
│                                                                                │
│  Foreground service (mic + WSS keepalive while screen off)                     │
└────────────────────────────────────────────────────────────────────────────────┘
                          │  single TLS WebSocket
                          ▼
              wss://api.tenclass.net/xiaozhi/v1/
                          │
              ┌───────────┴───────────┐
              │  xiaozhi cloud (existing)                        │
              │  • STT  • LLM  • TTS                             │
              │  • routes tools/call ───────────┐                │
              └─────────────────────────────────┼────────────────┘
                                               │  (cloud-to-cloud)
                                               ▼
                                  ┌─────────────────────────────┐
                                  │  Your REMOTE MCP server     │
                                  │  (xiaozhi-wss Python, runs  │
                                  │   on home server / VPS)     │
                                  │   wss→ api.xiaozhi.me/mcp/  │
                                  │   tools: news/stock/music   │
                                  └─────────────────────────────┘
```

The Android app in Plan 1 has **one outbound connection** (the device session).
Tools are not its concern — they are served by your remote `xiaozhi-wss`,
routed by the cloud. The app may display tool invocations in a drawer for
transparency, but hosts nothing.

## Prerequisites

1. A registered xiaozhi account at `xiaozhi.me`.
2. Your `xiaozhi-wss` Python server running somewhere reachable (home
   server / VPS / Termux) with a valid JWT, registered to the **same agent
   account**. Without this, voice chat works but no tools fire.
3. Optional: a 2nd Android device or emulator for activation testing (the
   verification code is bound to a single device identity).

## Auth & protocol (mirrors `py-xiaozhi`)

### Activation v2 (mandatory on official server)
- Generate the `efuse.json` equivalent in `EncryptedSharedPreferences`:
  - `mac_address` — a persisted **locally-administered MAC** (Android does not
    expose the real MAC past API 11). Format: lowercase colon-separated.
  - `serial_number = "SN-" + md5(mac).take(8).uppercase() + "-" + mac-no-colons`
    (see `py-xiaozhi/src/utils/device_fingerprint.py:226-228`).
  - `hmac_key = sha256(hostname ‖ mac ‖ ANDROID_ID)`
  - `activation_status: Boolean`
- `POST https://api.tenclass.net/xiaozhi/ota/` with headers:
  ```
  Device-Id: <mac>
  Client-Id: <uuid>
  Content-Type: application/json
  User-Agent: bread-compact-wifi/xiaozhi-android-1.0.0
  Accept-Language: zh-CN
  Activation-Version: 2.0.0
  ```
  Body:
  ```json
  {
    "application": {"version": "1.0.0", "elf_sha256": "<hmac_key>"},
    "board": {"type": "bread-compact-wifi", "name": "xiaozhi-android", "ip": "<lan_ip>", "mac": "<mac>"}
  }
  ```
- If the response contains `activation.challenge`:
  1. Show the `activation.code` (6-digit) prominently + a QR/link to
     `https://xiaozhi.me/`.
  2. Compute `hmac = HMAC-SHA256(hmac_key, challenge).hex()`.
  3. `POST <ota_url>/activate` with `Activation-Version: 2` header:
     ```json
     {"Payload": {"algorithm": "hmac-sha256", "serial_number": "<SN>",
                  "challenge": "<challenge>", "hmac": "<hex>"}}
     ```
  4. `200` → activated (set `activation_status=true`). `202` → user has not
     entered the code yet; retry every 5 s, up to 60 tries.

### WebSocket session
- Headers on the WS upgrade:
  ```
  Authorization: Bearer test-token
  Protocol-Version: 1
  Device-Id: <mac>
  Client-Id: <uuid>
  ```
- After connect, send `hello`:
  ```json
  {
    "type": "hello",
    "version": 1,
    "features": {"mcp": true},
    "transport": "websocket",
    "audio_params": {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 20}
  }
  ```
- Declare `features:{mcp:true}` even though Plan 1 registers no tools — this
  lets the cloud send `tools/list` so we can respond with an empty list, and
  the cloud then falls back to your remote MCP server for actual tool calls.
- All messages carry `session_id`. Text frames are JSON control; binary frames
  are Opus audio.

### In-session messages
| Direction | Message | Notes |
|---|---|---|
| C→S | `{type:"listen", state:"start", mode:"manual"}` | PTT pressed |
| C→S | `{type:"listen", state:"stop"}` | PTT released |
| C→S | `{type:"abort"}` | Barge-in / cancel TTS |
| C→S | binary Opus frames | While LISTENING (every 20 ms) |
| S→C | `{type:"hello", transport:"websocket", ...}` | Validate `transport=="websocket"` |
| S→C | `{type:"tts", state:"start"\|"stop", text?}` | TTS boundaries; audio arrives as binary between |
| S→C | `{type:"stt", text}` | Recognised user utterance |
| S→C | `{type:"llm", emotion}` | LLM emotion update (UI expression) |
| S→C | `{type:"mcp", payload:<jsonrpc>}` | Tool activity forwarded from your remote server — display in drawer |
| S→C | `{type:"iot", commands?}` | For Plan 1 respond with empty descriptors/states |

## Audio contract
| Param | Value |
|---|---|
| Input sample rate | 16000 Hz |
| Output sample rate | **24000 Hz** (official server; 3rd-party = 16 kHz) |
| Channels | 1 (mono) |
| Codec | Opus, `APPLICATION_VOIP` |
| Frame duration | **20 ms** (= 320 samples in / 480 out) |
| Frame size in | 16 kHz × 20 ms = 320 samples int16 |
| Frame size out | 24 kHz × 20 ms = 480 samples int16 |

Bundle `libopus.so` per ABI — copy from
[`py-xiaozhi/libs/libopus/`](../../py-xiaozhi/libs/libopus) (arm64-v64, x86_64).
JNI wrapper (`APPLICATION_VOIP`, 20 ms frames). Use Android's
`AudioRecord` (16 kHz mono) and `AudioTrack` (24 kHz mono,
`PERFORMANCE_MODE_LOW_LATENCY`).

## Module layout

```
app/src/main/java/com/example/xiaozhi/
├── XiaozhiApp.kt                       // Application, Hilt
├── di/
├── core/
│   ├── protocol/
│   │   ├── WebSocketProtocol.kt        // OkHttp WSS, headers, hello handshake
│   │   ├── ProtocolMessage.kt          // @Serializable: hello/listen/abort/tts/stt/llm/iot/mcp
│   │   └── SessionStore.kt             // session_id lifecycle
│   ├── activation/
│   │   ├── OtaClient.kt                // POST /xiaozhi/ota/ + /ota/activate
│   │   ├── DeviceFingerprint.kt        // mac, serial, hmac_key
│   │   └── ActivationManager.kt        // v2 challenge loop (5 s × 60)
│   └── identity/EfuseStore.kt          // EncryptedSharedPreferences
├── audio/
│   ├── AudioCapture.kt                 // AudioRecord 16k mono → PCM frames
│   ├── AudioPlayer.kt                  // AudioTrack 24k mono ← PCM frames
│   ├── OpusCodec.kt + opus_jni.cpp     // libopus via JNI
│   └── Resampler.kt                    // device-native ↔ 16k/24k
├── state/
│   └── ChatStateMachine.kt             // IDLE ⇄ LISTENING ⇄ SPEAKING
├── iot/
│   └── ThingManager.kt                 // empty registry for Plan 1
├── service/
│   └── VoiceForegroundService.kt       // mic + WSS while screen off
└── ui/
    ├── theme/                          // Material 3
    ├── nav/XiaozhiNavGraph.kt
    ├── activation/ActivationScreen.kt + VM
    ├── chat/ChatScreen.kt + VM
    ├── components/PttButton.kt, TranscriptBubble.kt, ToolActivityDrawer.kt
    └── settings/SettingsScreen.kt
```

## Phased delivery

### Phase 0 — Bootstrap *(~0.5 day)*
Kotlin + Jetpack Compose, minSdk 26, target 34. Hilt, Coroutines + Flow,
Kotlinx Serialization, OkHttp 4, Room, DataStore, EncryptedSharedPreferences.
**Verify**: empty app builds and runs.

### Phase 1 — Identity & activation *(~2 days)*
`EfuseStore`, `OtaClient`, `ActivationManager`, `ActivationScreen`
(prominent 6-digit code + QR to `xiaozhi.me`).
**Verify**: device appears in your xiaozhi account; OTA returns `websocket.url/token`.

### Phase 2 — Audio + protocol core *(~3–4 days)*
- Bundle `libopus.so` per ABI from `py-xiaozhi/libs/libopus/`.
- JNI wrapper (`APPLICATION_VOIP`, 20 ms).
- `WebSocketProtocol` with the 4 headers + `hello` handshake.
- `AudioCapture` (16 k → Opus) + `AudioPlayer` (Opus → 24 k).
- `ChatStateMachine` + PTT.
- Handle `tts/stt/llm/abort`.
- Foreground service for mic + WSS.
**Verify**: full voice round-trip — push FAB, speak, hear reply, see transcript.

### Phase 3 — Tool-activity UI + IoT plumbing *(~1.5 days)*
- The cloud may forward `{type:"mcp", payload:...}` envelopes from your remote
  tools — surface them in `ToolActivityDrawer` ("📋 `get_klse_stock_data(code=1155)` → …").
- Handle `iot` descriptors request: respond with empty list for Plan 1.
**Verify**: trigger a tool via voice ("what's Maybank's price") → see remote MCP
server fire in the drawer.

### Phase 4 — Polish *(~2 days)*
Foreground service (`FOREGROUND_SERVICE_MICROPHONE` on Android 14+), runtime
permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `INTERNET`,
`FOREGROUND_SERVICE`). zh-CN primary strings. Wake word **optional**
(`sherpa-onnx` Android AAR, ~30 MB — defer if APK size matters). Release build.

## Risks

| Risk | Mitigation |
|---|---|
| Android random MAC to apps since API 11 → unstable device identity | Persist a locally-administered MAC in `EfuseStore`; reuse `BOARD_TYPE="bread-compact-wifi"` |
| Cert verification disabled in `py-xiaozhi` (`ssl._create_unverified_context`) | OkHttp verifies by default; allow opt-in trust-all in **dev builds only** |
| `api.tenclass.net` rate-limits unknown boards | Reuse `BOARD_TYPE="bread-compact-wifi"`; if blocked, register a new board type upstream |
| TTS playback underrun → chopped audio | Pre-buffer ~3 frames before `AudioTrack.start()`, `PERFORMANCE_MODE_LOW_LATENCY` |
| Voice works but no tools fire | Confirm your remote `xiaozhi-wss` is registered to the same agent account |
| Mic killed in background | Foreground service with persistent notification + `WorkManager` watchdog |

## Dependencies (Python → Android)

| py-xiaozhi lib | Android replacement |
|---|---|
| `opuslib` | `libopus.so` via JNI (~250 KB/ABI) |
| `sounddevice` | `AudioRecord` / `AudioTrack` |
| `websockets` | `okhttp3.WebSocket` |
| `aiohttp` | `Retrofit` + `OkHttp` |
| `sherpa-onnx` (optional, Phase 4) | official sherpa-onnx Android AAR |
