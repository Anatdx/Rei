cmake_minimum_required(VERSION 3.22.1)
project("reijni" CXX C)

add_library(reijni SHARED reijni.cpp)
target_include_directories(reijni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/uapi)
target_link_libraries(reijni PRIVATE log)

# YukiSU kernelsu JNI (com.anatdx.yukisu.Natives) for allowlist
add_library(kernelsu SHARED
  yukisu/jni.c
  yukisu/legacy.c
  yukisu/ksu.cpp
)
target_include_directories(kernelsu PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/yukisu)
target_link_libraries(kernelsu PRIVATE log)
