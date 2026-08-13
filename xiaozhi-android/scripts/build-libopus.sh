#!/usr/bin/env bash
# Builds libopus.so for the 3 Android ABIs we ship.
#
# Output: /home/vunloon/dev/test2/xiaozhi-android/app/src/main/jniLibs/<abi>/libopus.so
set -euo pipefail

NDK_ROOT="${NDK_ROOT:-/home/vunloon/android-sdk/ndk/26.3.11579264}"
OPUS_SRC="${OPUS_SRC:-/tmp/opus-1.5.2}"
API_LEVEL="${API_LEVEL:-26}"
OUT_ROOT="${OUT_ROOT:-/home/vunloon/dev/test2/xiaozhi-android/app/src/main/jniLibs}"
WORK_ROOT="${WORK_ROOT:-/tmp/opus-build}"

command -v make >/dev/null || { echo "make is required" >&2; exit 1; }

if [[ ! -d "$OPUS_SRC" ]]; then
  echo "Opus source not found at $OPUS_SRC" >&2
  exit 1
fi

declare -A ABI_TO_TRIPLE=(
  ["arm64-v8a"]="aarch64-linux-android"
  ["armeabi-v7a"]="armv7a-linux-androideabi"
  ["x86_64"]="x86_64-linux-android"
)

build_one() {
  local abi="$1"; local triple="${ABI_TO_TRIPLE[$abi]}"
  local build_dir="$WORK_ROOT/$abi"
  local toolchain="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
  local cc="$toolchain/bin/${triple}${API_LEVEL}-clang"
  local cxx="$toolchain/bin/${triple}${API_LEVEL}-clang++"
  local ar="$toolchain/bin/llvm-ar"
  local ranlib="$toolchain/bin/llvm-ranlib"
  local strip="$toolchain/bin/llvm-strip"

  echo "==> [$abi] configure"
  rm -rf "$build_dir"; mkdir -p "$build_dir"
  (
    cd "$build_dir"
    "$OPUS_SRC/configure" \
      --host="$triple" \
      --prefix="$build_dir/install" \
      --disable-shared \
      --enable-static \
      --disable-extra-programs \
      --disable-doc \
      --disable-stack-protector \
      CC="$cc" CXX="$cxx" AR="$ar" RANLIB="$ranlib" STRIP="$strip" \
      CFLAGS="-O2 -fPIC -DANDROID --target=${triple}${API_LEVEL}" \
      LDFLAGS="--target=${triple}${API_LEVEL}"
  )

  echo "==> [$abi] make"
  make -C "$build_dir" -j"$(nproc)" install

  # Wrap the static lib into a shared .so (Android loads .so via System.loadLibrary).
  # The -soname is critical: without it, downstream linkers (CMake IMPORTED target)
  # record the absolute build path in the NEEDED entry, breaking runtime dlopen.
  local out_dir="$OUT_ROOT/$abi"
  mkdir -p "$out_dir"
  echo "==> [$abi] link libopus.so"
  "$cc" -shared -o "$out_dir/libopus.so" \
    -Wl,-soname,libopus.so \
    -Wl,--whole-archive "$build_dir/install/lib/libopus.a" -Wl,--no-whole-archive \
    -lm -llog
  "$strip" --strip-unneeded "$out_dir/libopus.so"
  echo "==> [$abi] done -> $out_dir/libopus.so"
}

for abi in arm64-v8a armeabi-v7a x86_64; do
  build_one "$abi"
done

echo
echo "All ABIs built:"
ls -la "$OUT_ROOT"/*/
