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
