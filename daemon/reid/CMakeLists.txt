cmake_minimum_required(VERSION 3.14)
project(reid VERSION 1.0.0 LANGUAGES C CXX)

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

set(GENERATED_DIR ${CMAKE_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${GENERATED_DIR})

# Generate embedded assets
set(ASSETS_DIR ${CMAKE_SOURCE_DIR}/assets)
set(ASSETS_CPP ${GENERATED_DIR}/assets_data.cpp)
set(VERSION_CPP ${CMAKE_SOURCE_DIR}/src/defs.cpp)

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
    src/core/restorecon.cpp
    src/core/assets.cpp
    src/core/hide_bootloader.cpp
    src/core/allowlist.cpp
    src/flash/flash_ak3.cpp
    src/flash/flash_partition.cpp
    src/init_event.cpp
    src/murasaki_dispatch.cpp
    src/binder/murasaki_binder.cpp
    src/binder/murasaki_service.cpp
    src/binder/murasaki_ipc.cpp
    src/binder/shizuku_service.cpp
    src/ksud/ksucalls.cpp
    src/ksud/feature.cpp
    src/ksud/debug.cpp
    src/ksud/su.cpp
    src/ksud/umount.cpp
    src/ksud/ksud_cli.cpp
    src/ksud/module/module.cpp
    src/ksud/module/module_config.cpp
    src/ksud/module/metamodule.cpp
    src/ksud/boot/boot_patch.cpp
    src/ksud/boot/tools.cpp
    src/ksud/boot/apk_sign.cpp
    src/ksud/profile/profile.cpp
    src/ksud/sepolicy/sepolicy.cpp
    ${ASSETS_CPP}
)

# Android：单二进制 reid = reid + apd + ksud（硬链接 apd/ksud 同 inode），切换需重启。
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
  set(APD_VERSION_CODE "0")
  set(APD_VERSION_NAME "reid-apd-ksud")
  set(APD_VERSION_HPP ${GENERATED_DIR}/apd_version.hpp)
  configure_file(
      ${CMAKE_SOURCE_DIR}/src/apd/version.hpp.in
      ${APD_VERSION_HPP}
      @ONLY
  )
  set(APD_SUPERCALL_SOURCES
      src/apd/supercall.cpp
      src/apd/log.cpp
      src/apd/utils.cpp
      src/apd/package.cpp
      src/apd/defs.cpp
  )
  add_library(reid_apd_supercall STATIC ${APD_SUPERCALL_SOURCES})
  target_include_directories(reid_apd_supercall PUBLIC ${CMAKE_SOURCE_DIR}/src/apd ${GENERATED_DIR})
  target_link_libraries(reid_apd_supercall PRIVATE log)
  # 完整 apd CLI 并入 reid，供 argv[0]==apd 时使用
  list(APPEND SOURCES
      src/apd/cli.cpp
      src/apd/event.cpp
      src/apd/module.cpp
      src/apd/metamodule.cpp
      src/apd/sepolicy.cpp
      src/apd/pty.cpp
      src/apd/restorecon.cpp
      src/apd/assets.cpp
      src/apd/apd.cpp
  )
endif()

include_directories(${CMAKE_SOURCE_DIR}/src/ksud ${CMAKE_SOURCE_DIR}/src ${CMAKE_SOURCE_DIR}/src/apd)
include_directories(${picosha2_SOURCE_DIR})
include_directories(${miniz_SOURCE_DIR})
include_directories(${GENERATED_DIR})

add_executable(reid ${SOURCES})

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE pthread)
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE dl log)
    target_link_libraries(reid PRIVATE reid_apd_supercall)
endif()

target_link_libraries(reid PRIVATE z miniz)

install(TARGETS reid DESTINATION bin)
