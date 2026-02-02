cmake_minimum_required(VERSION 3.14)
project(reid VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    message(STATUS "Using ccache: ${CCACHE_PROGRAM}")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden")

if(APPLE)
    set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG")
else()
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fuse-ld=lld -Wl,--gc-sections -Wl,--strip-all -flto")
    set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG -flto")
endif()

# Fetch third-party dependencies
include(FetchContent)

# PicoSHA2 - SHA-256 implementation
FetchContent_Declare(
    picosha2
    GIT_REPOSITORY https://github.com/okdshin/PicoSHA2.git
    GIT_TAG v1.0.1
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(picosha2)

# miniz - Lightweight ZIP library
FetchContent_Declare(
    miniz
    GIT_REPOSITORY https://github.com/richgel999/miniz.git
    GIT_TAG 3.0.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(miniz)

# Generate embedded assets
set(ASSETS_DIR ${CMAKE_SOURCE_DIR}/assets)
set(GENERATED_DIR ${CMAKE_BINARY_DIR}/generated)
set(ASSETS_CPP ${GENERATED_DIR}/assets_data.cpp)
set(VERSION_CPP ${CMAKE_SOURCE_DIR}/src/defs.cpp)

# Create generated directory
file(MAKE_DIRECTORY ${GENERATED_DIR})

# Generate version file at configure time
find_package(Python3 REQUIRED COMPONENTS Interpreter)
execute_process(
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_SOURCE_DIR}/scripts/generate_version.py ${VERSION_CPP}
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    RESULT_VARIABLE VERSION_RESULT
)
if(NOT VERSION_RESULT EQUAL 0)
    message(WARNING "Failed to generate version file, using defaults")
endif()

# Run embed_assets.py to generate assets_data.cpp
add_custom_command(
    OUTPUT ${ASSETS_CPP}
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_SOURCE_DIR}/scripts/embed_assets.py 
            ${ASSETS_DIR} ${ASSETS_CPP}
    DEPENDS ${CMAKE_SOURCE_DIR}/scripts/embed_assets.py
    COMMENT "Generating embedded assets..."
)

set(SOURCES
    src/main.cpp
    src/cli.cpp
    src/defs.cpp
    src/log.cpp
    src/utils.cpp
    src/core/ksucalls.cpp
    src/core/feature.cpp
    src/core/restorecon.cpp
    src/core/assets.cpp
    src/module/module.cpp
    src/module/module_config.cpp
    src/module/metamodule.cpp
    src/boot/boot_patch.cpp
    src/boot/tools.cpp
    src/boot/apk_sign.cpp
    src/profile/profile.cpp
    src/sepolicy/sepolicy.cpp
    src/su.cpp
    src/init_event.cpp
    src/umount.cpp
    src/debug.cpp
    src/core/hide_bootloader.cpp
    src/flash/flash_ak3.cpp
    src/flash/flash_partition.cpp
    src/hymo/hymo_utils.cpp
    src/hymo/mount/hymofs.cpp
    src/binder/murasaki_binder.cpp
    src/binder/murasaki_service.cpp
    src/binder/murasaki_ipc.cpp
    src/binder/shizuku_service.cpp
    ${ASSETS_CPP}
)

include_directories(${CMAKE_SOURCE_DIR}/src)
include_directories(${picosha2_SOURCE_DIR})
include_directories(${miniz_SOURCE_DIR})
include_directories(${GENERATED_DIR})

add_executable(reid ${SOURCES})

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE pthread)
endif()

# libbinder_ndk headers and dl library for dynamic loading (Android only)
# We use dlopen() to load libbinder_ndk.so at runtime, so no direct linking needed
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE dl)
endif()

target_link_libraries(reid PRIVATE z miniz)

install(TARGETS reid DESTINATION bin)
