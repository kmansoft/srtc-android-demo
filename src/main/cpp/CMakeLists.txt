cmake_minimum_required(VERSION 3.22.1)

project("srtctest")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED YES)

include(ExternalProject)
include(FetchContent)

add_library(srtctest
        SHARED
        jni_class_map.h
        jni_class_map.cpp
        jni_error.h
        jni_error.cpp
        jni_util.h
        jni_util.cpp
        jni_peer_connection.h
        jni_peer_connection.cpp
        srtctest_main.cpp
)

target_link_libraries(srtctest
        android
        log
)

# BoringSSL
# Has to be available during configure because dependencies do find_package(OPENSSL)

FetchContent_Declare(boringssl
        GIT_REPOSITORY "https://boringssl.googlesource.com/boringssl"
        GIT_TAG "origin/master"
        GIT_SHALLOW ON
)

FetchContent_GetProperties(boringssl)
if(NOT boringssl_POPULATED)
    FetchContent_MakeAvailable(boringssl)
endif()

set(BORINGSSL_SOURCE_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/boringssl-src")
set(BORINGSSL_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/boringssl-build")

set(BORINGSSL_INSTALL_DIR "${BORINGSSL_SOURCE_DIR}/install")

if(NOT EXISTS "${BORINGSSL_INSTALL_DIR}")
    execute_process(COMMAND "${CMAKE_COMMAND}"
            "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
            "-DANDROID_ABI=${ANDROID_ABI}"
            "-DANDROID_PLATFORM=android-29"
            "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
            "-DOPENSSL_SMALL=ON"
            "-GNinja"
            "-S" "${BORINGSSL_SOURCE_DIR}"
            "-B" "${BORINGSSL_BUILD_DIR}"
        )
    execute_process(COMMAND "${CMAKE_COMMAND}"
            "--build"
            "${BORINGSSL_BUILD_DIR}"
            "--target" "crypto"
            "--target" "ssl"
            "--target" "install"
        )
endif()

set(OPENSSL_ROOT_DIR "${BORINGSSL_INSTALL_DIR}")
list(APPEND CMAKE_FIND_ROOT_PATH "${OPENSSL_ROOT_DIR}")

# OPUS

ExternalProject_Add(
        opus
        GIT_REPOSITORY "https://github.com/xiph/opus.git"
        GIT_TAG "v1.5.2"
        GIT_SHALLOW ON
        SOURCE_DIR "external/opus"
        INSTALL_COMMAND ""
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=android-29
            -DCMAKE_BUILD_TYPE=RelWithDebInfo
)

target_include_directories(srtctest PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/external/opus/include")

target_link_directories(srtctest PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/opus-prefix/src/opus-build")

add_dependencies(srtctest opus)

target_link_libraries(
        srtctest
        libopus.a
)

# srtc

add_subdirectory(
     "srtc"
)

add_dependencies(srtctest srtc)

target_link_libraries(srtctest
        srtc
)

# LibSRTP

target_link_directories(srtctest PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/srtc/srtp-prefix/src/srtp-build")
