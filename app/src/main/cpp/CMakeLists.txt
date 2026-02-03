cmake_minimum_required(VERSION 3.22.1)
project("apjni" CXX)

add_library(apjni SHARED apjni.cpp)
target_include_directories(apjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/uapi)
target_link_libraries(apjni PRIVATE log)
