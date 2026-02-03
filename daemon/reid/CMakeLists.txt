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

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
  # Lua (for apd/KernelPatch module scripts)
  FetchContent_Declare(
      lua
      URL https://www.lua.org/ftp/lua-5.5.0.tar.gz
      URL_HASH SHA256=57ccc32bbbd005cab75bcc52444052535af691789dba2b9016d5c50640d68b3d
  )
  FetchContent_Populate(lua)
  add_library(lua STATIC
      ${lua_SOURCE_DIR}/src/lapi.c ${lua_SOURCE_DIR}/src/lcode.c ${lua_SOURCE_DIR}/src/lctype.c
      ${lua_SOURCE_DIR}/src/ldebug.c ${lua_SOURCE_DIR}/src/ldo.c ${lua_SOURCE_DIR}/src/ldump.c
      ${lua_SOURCE_DIR}/src/lfunc.c ${lua_SOURCE_DIR}/src/lgc.c ${lua_SOURCE_DIR}/src/llex.c
      ${lua_SOURCE_DIR}/src/lmem.c ${lua_SOURCE_DIR}/src/lobject.c ${lua_SOURCE_DIR}/src/lopcodes.c
      ${lua_SOURCE_DIR}/src/lparser.c ${lua_SOURCE_DIR}/src/lstate.c ${lua_SOURCE_DIR}/src/lstring.c
      ${lua_SOURCE_DIR}/src/ltable.c ${lua_SOURCE_DIR}/src/ltm.c ${lua_SOURCE_DIR}/src/lundump.c
      ${lua_SOURCE_DIR}/src/lvm.c ${lua_SOURCE_DIR}/src/lzio.c
      ${lua_SOURCE_DIR}/src/lauxlib.c ${lua_SOURCE_DIR}/src/lbaselib.c ${lua_SOURCE_DIR}/src/lcorolib.c
      ${lua_SOURCE_DIR}/src/ldblib.c ${lua_SOURCE_DIR}/src/liolib.c ${lua_SOURCE_DIR}/src/lmathlib.c
      ${lua_SOURCE_DIR}/src/loslib.c ${lua_SOURCE_DIR}/src/lstrlib.c ${lua_SOURCE_DIR}/src/ltablib.c
      ${lua_SOURCE_DIR}/src/lutf8lib.c ${lua_SOURCE_DIR}/src/loadlib.c ${lua_SOURCE_DIR}/src/linit.c
  )
  target_include_directories(lua PUBLIC ${lua_SOURCE_DIR}/src)
  target_compile_definitions(lua PRIVATE LUA_USE_POSIX LUA_USE_DLOPEN)
  target_link_libraries(lua PRIVATE dl)

  # APd version (for KernelPatch backend)
  execute_process(
      COMMAND git rev-list --count HEAD
      WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
      OUTPUT_VARIABLE APD_GIT_COUNT
      OUTPUT_STRIP_TRAILING_WHITESPACE
  )
  execute_process(
      COMMAND git describe --tags --always
      WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
      OUTPUT_VARIABLE APD_GIT_DESC
      OUTPUT_STRIP_TRAILING_WHITESPACE
  )
  if(APD_GIT_COUNT STREQUAL "")
      set(APD_GIT_COUNT "0")
  endif()
  if(APD_GIT_DESC STREQUAL "")
      set(APD_GIT_DESC "0.0.0")
  endif()
  math(EXPR APD_VERSION_CODE "10000 + 200 + ${APD_GIT_COUNT}")
  string(REGEX REPLACE "^v" "" APD_VERSION_NAME "${APD_GIT_DESC}")
  set(APD_VERSION_HPP ${GENERATED_DIR}/apd_version.hpp)
  configure_file(
      ${CMAKE_SOURCE_DIR}/src/apd/version.hpp.in
      ${APD_VERSION_HPP}
      @ONLY
  )
endif()

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
    src/binder/murasaki_binder.cpp
    src/binder/murasaki_service.cpp
    src/binder/murasaki_ipc.cpp
    src/binder/shizuku_service.cpp
    ${ASSETS_CPP}
)

# APd static lib (KernelPatch/APatch backend) - full implementation only on Android
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
  set(APD_SOURCES
      src/apd/apd.cpp src/apd/cli.cpp src/apd/defs.cpp src/apd/log.cpp src/apd/utils.cpp
      src/apd/assets.cpp src/apd/supercall.cpp src/apd/package.cpp src/apd/module.cpp
      src/apd/metamodule.cpp src/apd/event.cpp src/apd/sepolicy.cpp src/apd/restorecon.cpp
      src/apd/pty.cpp
  )
  add_library(reid_apd STATIC ${APD_SOURCES})
  target_include_directories(reid_apd PUBLIC
      ${CMAKE_SOURCE_DIR}/src/apd
      ${GENERATED_DIR}
  )
  target_compile_definitions(reid_apd PRIVATE APD_USE_LUA)
  target_link_libraries(reid_apd PRIVATE lua)
else()
  add_library(reid_apd STATIC src/apd_stub.cpp)
  target_include_directories(reid_apd PUBLIC ${CMAKE_SOURCE_DIR}/src)
endif()

include_directories(${CMAKE_SOURCE_DIR}/src)
include_directories(${picosha2_SOURCE_DIR})
include_directories(${miniz_SOURCE_DIR})
include_directories(${GENERATED_DIR})

add_executable(reid ${SOURCES})

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE pthread)
endif()

# Android: dl for dynamic loading, log for apd logging (__android_log_vprint)
if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(reid PRIVATE dl log)
endif()

target_link_libraries(reid PRIVATE z miniz reid_apd)

install(TARGETS reid DESTINATION bin)
