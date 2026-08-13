cmake_minimum_required(VERSION 3.22.1)
project(xiaozhi_opus LANGUAGES CXX)

# Path to the prebuilt libopus.so shipped under jniLibs/<abi>/.
# cpp/ and jniLibs/ are siblings under app/src/main/.
set(OPUS_PREBUILT_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(OPUS_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/opus/include")

if(NOT EXISTS "${OPUS_PREBUILT_DIR}/libopus.so")
    message(FATAL_ERROR "libopus.so not found at ${OPUS_PREBUILT_DIR}/libopus.so. Run scripts/build-libopus.sh first.")
endif()

add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION "${OPUS_PREBUILT_DIR}/libopus.so"
)

add_library(opus_jni SHARED
    opus_jni.cpp
)

target_include_directories(opus_jni PRIVATE
    "${OPUS_INCLUDE_DIR}"
)

target_link_libraries(opus_jni
    opus
    log
)

find_library(android-log log REQUIRED)
