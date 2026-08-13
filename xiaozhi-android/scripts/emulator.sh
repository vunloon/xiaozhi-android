#!/usr/bin/env bash
# Convenience wrapper around the Android emulator for the xiaozhi-android app.
#
# Usage:
#   ./scripts/emulator.sh start     # boot the headless emulator (waits for boot)
#   ./scripts/emulator.sh install   # build + install the debug APK
#   ./scripts/emulator.sh run       # ensure emulator is up, install + launch
#   ./scripts/emulator.sh logcat    # tail app logs (xiaozhi + okhttp + native)
#   ./scripts/emulator.sh stop      # kill the emulator
#   ./scripts/emulator.sh grant     # grant RECORD_AUDIO + POST_NOTIFICATIONS
#   ./scripts/emulator.sh reset     # uninstall + reinstall (wipes efuse identity)
#   ./scripts/emulator.sh screenshot [path]
set -euo pipefail

source "$HOME/.android-env" 2>/dev/null || true

AVD_NAME="${AVD_NAME:-xiaozhi_test}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_HOME/emulator/emulator}"
GRADLEW="$(cd "$(dirname "$0")/.." && pwd)/gradlew"
PKG="com.xiaozhi.client.debug"
ACTIVITY="$PKG/com.xiaozhi.client.MainActivity"

# Pick the first attached device serial (emulator or USB). Falls back to
# emulator-5554 only when nothing is attached yet — caller likely intends
# to talk to a freshly-started emulator.
pick_serial() {
    local first
    first="$("$ADB" devices | awk 'NF && $2=="device" && !/List/ {print $1; exit}')"
    echo "${first:-emulator-5554}"
}

any_device_attached() {
    "$ADB" devices | awk 'NF && $2=="device" && !/List/ {found=1} END {exit !found}'
}

emulator_running() {
    pgrep -f "qemu.*$AVD_NAME" >/dev/null 2>&1
}

# WINDOW=1 (default if DISPLAY is set) shows the GUI; WINDOW=0 forces -no-window.
want_window() {
    if [[ "${WINDOW:-}" == "0" ]]; then return 1; fi
    if [[ "${WINDOW:-}" == "1" ]]; then return 0; fi
    [[ -n "${DISPLAY:-}" ]]
}

# AUDIO=1 (default) keeps emulator sound on; AUDIO=0 passes -no-audio (e.g.
# headless CI without host audio hardware).
want_audio() {
    [[ "${AUDIO:-1}" == "1" ]]
}

adb_wait_boot() {
    local serial="$1"
    echo "Waiting for $serial to finish booting…"
    for i in $(seq 1 120); do
        if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            echo "Boot complete (after ${i}x2s)"
            return 0
        fi
        sleep 2
    done
    echo "Boot timeout"; return 1
}

ensure_emulator_up() {
    if any_device_attached; then
        local s; s="$(pick_serial)"
        echo "Device already attached: $s"
        # Make sure it's fully booted before we use it.
        adb_wait_boot "$s" || true
        return 0
    fi
    if emulator_running; then
        echo "Emulator process exists but not yet attached to adb — waiting…"
    else
        echo "No device attached — starting emulator (AVD: $AVD_NAME)"
        mkdir -p ~/.android/avd
        local window_args=()
        if want_window; then
            echo "  (windowed — set WINDOW=0 to force headless)"
        else
            echo "  (headless — no DISPLAY; set WINDOW=1 to try anyway)"
            window_args+=(-no-window)
        fi
        local audio_args=()
        if want_audio; then
            echo "  (audio on — set AUDIO=0 to force -no-audio)"
            # -allow-host-audio: without it the emulator zeroes out host mic
            # audio, so the guest's AudioRecord reads pure silence.
            audio_args+=(-allow-host-audio)
        else
            echo "  (audio off — AUDIO=0 set; no host sound)"
            audio_args+=(-no-audio)
        fi
        nohup "$EMULATOR" -avd "$AVD_NAME" \
            -no-boot-anim \
            -gpu swiftshader_indirect -no-snapshot \
            "${audio_args[@]}" \
            "${window_args[@]}" \
            > /tmp/emulator.log 2>&1 &
        echo "  emulator PID: $!  (log: /tmp/emulator.log)"
    fi
    echo "Waiting for adb to see the emulator…"
    for i in $(seq 1 60); do
        if any_device_attached; then break; fi
        sleep 2
    done
    if ! any_device_attached; then
        echo "ERROR: emulator never attached to adb. Check /tmp/emulator.log"; exit 1
    fi
    adb_wait_boot "$(pick_serial)"
}

case "${1:-help}" in
    start)
        ensure_emulator_up
        ;;
    install)
        SERIAL="${SERIAL:-$(pick_serial)}"
        "$GRADLEW" :app:assembleDebug
        "$ADB" -s "$SERIAL" install -r -t \
            app/build/outputs/apk/debug/app-debug.apk
        ;;
    run)
        ensure_emulator_up
        SERIAL="$(pick_serial)"
        "${0%/*}/emulator.sh" install
        "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
        "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
        "$ADB" -s "$SERIAL" logcat -c 2>/dev/null || true
        "$ADB" -s "$SERIAL" shell am start -n "$ACTIVITY"
        echo
        echo "Launched on $SERIAL. Tail logs with: $0 logcat"
        ;;
    grant)
        SERIAL="${SERIAL:-$(pick_serial)}"
        "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO
        "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
        ;;
    logcat)
        SERIAL="${SERIAL:-$(pick_serial)}"
        "$ADB" -s "$SERIAL" logcat -c 2>/dev/null || true
        "$ADB" -s "$SERIAL" logcat \
            OpusCodec:I ActivationManager:V OtaClient:V WebSocketProtocol:V \
            okhttp.OkHttpClient:I ChatStateMachine:V XiaozhiApp:I \
            AndroidRuntime:E *:F
        ;;
    screenshot)
        SERIAL="${SERIAL:-$(pick_serial)}"
        OUT="${2:-/tmp/xiaozhi-screen.png}"
        "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT"
        echo "Saved $OUT"
        ;;
    stop)
        SERIAL="${SERIAL:-$(pick_serial)}"
        "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
        ;;
    reset)
        SERIAL="${SERIAL:-$(pick_serial)}"
        "$ADB" -s "$SERIAL" uninstall "$PKG" 2>/dev/null || true
        "${0%/*}/emulator.sh" run
        ;;
    *)
        cat <<'USAGE'
Usage: emulator.sh <command>

  start      Boot the headless x86_64 emulator (creates AVD xiaozhi_test if missing)
  install    Build the debug APK and install it on the attached device
  run        Ensure emulator is up, build + install + grant mic + launch
  grant      Re-grant RECORD_AUDIO + POST_NOTIFICATIONS
  logcat     Tail xiaozhi-relevant logs (Ctrl-C to stop)
  screenshot [path]   Save a PNG screenshot (default /tmp/xiaozhi-screen.png)
  stop       Shut the emulator down
  reset      Uninstall + reinstall (wipes device identity so activation reruns)

The script auto-picks the first attached adb device, so it works on a real
phone too (USB debugging on, `adb devices` shows it).

Environment overrides:
  AVD_NAME, SERIAL, ADB, EMULATOR, ANDROID_HOME
USAGE
        ;;
esac
