cmake_minimum_required(VERSION 3.22.1)

project("srtctest")

add_subdirectory(
        "srtc"
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED YES)

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
        srtc
        android
        log
)

target_link_libraries(
        srtctest
)

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
)

target_include_directories(srtctest PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/external/opus/include")

target_link_directories(srtctest PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/opus-prefix/src/opus-build")

add_dependencies(srtctest opus)
