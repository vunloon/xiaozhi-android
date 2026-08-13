# AGENTS.md

Android voice-chat client (`com.xiaozhi.client`, debug APK suffix `.debug`) that talks to the official xiaozhi cloud (`wss://api.tenclass.net/xiaozhi/v1/`). Not a git repo. Companion planning docs live in `../plans/` (workspace root).

## Toolchain & env

- Env is **not** in `PATH` by default. Run `source ~/.android-env` (sets JDK 17, ANDROID_HOME=/home/vunloon/android-sdk, platform-tools, gradle 8.7) before gradle/adb/emulator.
- SDK at `/home/vunloon/android-sdk` (via `local.properties`), NDK `26.3.11579264`, CMake `3.22.1`, Gradle 8.7 (wrapper).

## Commands

```bash
./gradlew :app:assembleDebug            # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # unit tests (src/test: DeviceFingerprintTest, OtaWireFormatTest)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.xiaozhi.client.debug android.permission.RECORD_AUDIO
adb shell am start -n com.xiaozhi.client.debug/com.xiaozhi.client.MainActivity
```

- `scripts/emulator.sh` is the one-stop wrapper: `start|install|run|logcat|screenshot|grant|reset|stop`. `run` = build+install+grant+launch. Auto-picks first attached adb device.
- `reset` uninstalls → wipes the device identity in EncryptedSharedPreferences → activation code reruns.
- `libopus.so` is prebuilt per-ABI under `app/src/main/jniLibs/`; rebuild only via `scripts/build-libopus.sh` (needs opus source at `/tmp/opus-1.5.2`).
- No linter/formatter configured — there is no lint gate; build + unit tests are the check.

## Emulator audio gotchas (hard-won)

- Emulator must boot with `-allow-host-audio` or the guest's `AudioRecord` reads pure silence (ranchu HAL zeroes host audio). `scripts/emulator.sh` does this when `AUDIO=1` (default); `AUDIO=0` passes `-no-audio`.
- `AudioCapture` uses `MediaRecorder.AudioSource.MIC` (NOT `VOICE_COMMUNICATION`) — on the emulator's ranchu HAL only the mic/default source is wired to the host mic.
- On this Linux host, guest mic audio flows host-ALSA → PipeWire → qemu input node. The link can silently drop; symptom is STT returning single-char garbage like `我。` while `AudioCapture` logs `last packet=~20B` (silence). Reconnect:
  ```bash
  export XDG_RUNTIME_DIR=/run/user/1000
  pw-link "alsa_input.pci-0000_03_00.6.HiFi__hw_acp__source:capture_FL" "qemu-system-x86_64:input_FL"
  pw-link "alsa_input.pci-0000_03_00.6.HiFi__hw_acp__source:capture_FR" "qemu-system-x86_64:input_FR"
  ```
- The ACP digital mic is low-gain. Boost the PipeWire source (node 54) if levels are weak: `pw-cli set-param 54 Props "{ channelVolumes = [ 2.0 2.0 ] }"`.
- Debug logging in `AudioCapture.kt` (`pcm peak=…` when >200) tells you the guest is really capturing; real speech shows peaks in the thousands and 60–90B packets. It's diagnostic scaffolding, not product code.

## Wire-format specifics (api.tenclass.net)

- Sending a plain `listen start/stop` with text is ignored. Text input must go as `{"type":"listen","state":"detect","text":…}` — see `WebSocketProtocol.sendTextUtterance` (line ~134).
- Server sends the assistant's text in `tts.sentence_start`, not `tts start`; `ChatStateMachine` renders it there, and dedupes `stt` echo against the last `User` transcript entry.
- Client→server Opus at 16 kHz mono/20ms; server→client at 24 kHz mono/20ms.
- `ChatStateMachine` + `core/protocol` are the places that changed during protocol debugging — read them before assuming message handling.

## Playback note

`AudioPlayer.kt` consumes its queue with a plain `for (packet in channel)` loop. Do **not** switch to `consumeEach` — it cancels the channel and kills every TTS cycle after the first.
