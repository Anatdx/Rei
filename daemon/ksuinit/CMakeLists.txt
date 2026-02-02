cmake_minimum_required(VERSION 3.14)
project(reinit VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    message(STATUS "Using ccache: ${CCACHE_PROGRAM}")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden")
if(APPLE)
    set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG")
    set(CMAKE_CXX_FLAGS_MINSIZEREL "-Os -DNDEBUG")
else()
    set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG -flto")
    set(CMAKE_CXX_FLAGS_MINSIZEREL "-Os -DNDEBUG -flto")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fuse-ld=lld -Wl,--gc-sections -Wl,--strip-all -flto")
endif()

add_executable(reinit
    src/main.cpp
    src/init.cpp
    src/loader.cpp
    src/log.cpp
)

target_include_directories(reinit PRIVATE src)

# No external dependencies - pure C++ with POSIX/Linux APIs
