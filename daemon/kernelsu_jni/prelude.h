
#ifndef REI_KERNELSU_PRELUDE_H
#define REI_KERNELSU_PRELUDE_H

#include <android/log.h>
#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GetEnvironment() (*env)
#define NativeBridge(fn, rtn, ...)                                             \
  JNIEXPORT rtn JNICALL Java_com_anatdx_rei_KsuNatives_##fn(                    \
      JNIEnv *env, jclass clazz, __VA_ARGS__)
#define NativeBridgeNP(fn, rtn)                                                \
  JNIEXPORT rtn JNICALL Java_com_anatdx_rei_KsuNatives_##fn(JNIEnv *env,        \
                                                           jclass clazz)

#ifdef NDEBUG
#define LogDebug(...) (void)0
#else
#define LogDebug(...)                                                          \
  __android_log_print(ANDROID_LOG_DEBUG, "ReiKSU", __VA_ARGS__)
#endif

#endif

