#!/bin/sh
# llama.cpp's llama-server for Android, for models running on the phone itself.
# Output: app/src/main/jniLibs/arm64-v8a/libllamaserver.so (stripped)
set -e
here=$(cd "$(dirname "$0")" && pwd)
TAG=v0.8.0
NDK=${NDK:-$HOME/.zcode/android-build/sdk/ndk/28.1.13356709}
NINJA=${NINJA:-$HOME/.zcode/android-build/sdk/cmake/3.22.1/bin/ninja}
cd "$here/llama"
[ -d llama.cpp ] || git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git llama.cpp
git -C llama.cpp checkout -q "$TAG"
# Two builds: dotprod+fp16 is in every arm64 phone chip of the last years; i8mm (Snapdragon 8 Gen 1 and
# newer, Dimensity 9000 and newer) makes the matrix kernels much faster. The app picks by /proc/cpuinfo.
# Subprocesses are off for Android by default; the router mode needs them, and the app may start them
# because the binary lives in its library folder.
# Bionic only has posix_spawn_file_actions_addchdir_np from Android 14 on; the router never asks for it.
out="$here/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$out"
for variant in base i8mm; do
    case $variant in
        base) arch=armv8.2-a+dotprod+fp16; name=libllamaserver.so ;;
        i8mm) arch=armv8.6-a+dotprod+fp16+i8mm; name=libllamaserveri8mm.so ;;
    esac
    cmake -S llama.cpp -B "build-$variant" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DGGML_OPENMP=OFF \
        -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=$arch -DLLAMA_SUBPROCESS=ON "-DCMAKE_CXX_FLAGS=-DSUBPROCESS_HAVE_CWD=0" "-DCMAKE_C_FLAGS=-DSUBPROCESS_HAVE_CWD=0" \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON >/dev/null || { echo "cmake failed for $variant" >&2; exit 1; }
    cmake --build "build-$variant" --target llama-server -j 16 >/dev/null || { echo "cmake failed for $variant" >&2; exit 1; }
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$out/$name" "build-$variant/bin/llama-server"
done
ls -la "$out"/libllamaserver*.so
