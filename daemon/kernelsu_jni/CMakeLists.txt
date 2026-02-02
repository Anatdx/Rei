cmake_minimum_required(VERSION 3.10)
project(kernelsu_jni C)

add_library(kernelsu SHARED
    jni.c
    ksu.c
    legacy.c
)

target_include_directories(kernelsu PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(kernelsu ${log-lib})

