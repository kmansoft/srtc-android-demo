cmake_minimum_required(VERSION 3.22.1)

project("srtctest")

add_subdirectory(
        "srtc"
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED YES)

add_library(srtctest
        SHARED
        srtctest_main.cpp)

target_link_libraries(srtctest
        srtc
        android
        log)
