/* SPDX-License-Identifier: GPL-2.0-or-later */
/* APatch/KernelPatch supercall JNI for Rei - superkey elevation. */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "supercall.h"
#include "uapi/scdefs.h"

#define LOG_TAG "ReiApjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void ensureSuperKeyNonNull(JNIEnv *env, jstring super_key_jstr) {
    if (!super_key_jstr) {
        LOGE("Super key is null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "superKey must not be null");
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_ApNatives_nativeReady(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return JNI_FALSE;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return JNI_FALSE;
    jboolean result = sc_ready(key) ? JNI_TRUE : JNI_FALSE;
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint to_uid, jstring selinux_context_jstr) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    const char *sctx = nullptr;
    if (selinux_context_jstr) {
        sctx = env->GetStringUTFChars(selinux_context_jstr, nullptr);
    }
    struct su_profile profile {};
    profile.uid = getuid();
    profile.to_uid = (uid_t) to_uid;
    if (sctx) {
        strncpy(profile.scontext, sctx, sizeof(profile.scontext) - 1);
        profile.scontext[sizeof(profile.scontext) - 1] = '\0';
        env->ReleaseStringUTFChars(selinux_context_jstr, sctx);
    } else {
        profile.scontext[0] = '\0';
    }
    long rc = sc_su(key, &profile);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (rc < 0) {
        LOGE("nativeSu error: %ld", rc);
    }
    return (jlong) rc;
}

JNIEXPORT jstring JNICALL
Java_com_anatdx_rei_ApNatives_nativeSuPath(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewStringUTF("");
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewStringUTF("");
    char buf[SU_PATH_MAX_LEN] = {'\0'};
    long rc = sc_su_get_path(key, buf, sizeof(buf));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (rc < 0) return env->NewStringUTF("");
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeKernelPatchVersion(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return 0;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return 0;
    uint32_t ver = sc_kp_ver(key);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return (jlong) ver;
}

JNIEXPORT jstring JNICALL
Java_com_anatdx_rei_ApNatives_nativeDiag(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewStringUTF("");
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewStringUTF("");
    long hello = sc_hello(key);
    long kpver = (long) sc_kp_ver(key);
    long kver = (long) sc_k_ver(key);
    char build_time[512] = {'\0'};
    long bt_rc = sc_get_build_time(key, build_time, sizeof(build_time));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    std::string out;
    out += "hello=" + std::to_string(hello) + "\n";
    out += "kp_ver=" + std::to_string(kpver) + "\n";
    out += "kernel_ver=" + std::to_string(kver) + "\n";
    out += "build_time_rc=" + std::to_string(bt_rc) + "\n";
    if (bt_rc >= 0 && build_time[0]) {
        out += "build_time=";
        out += build_time;
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_anatdx_rei_ApNatives_nativeSuUids(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewIntArray(0);
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewIntArray(0);
    long num = sc_su_uid_nums(key);
    if (num <= 0) {
        env->ReleaseStringUTFChars(super_key_jstr, key);
        return env->NewIntArray(0);
    }
    std::vector<int> uids(static_cast<size_t>(num), 0);
    long n = sc_su_allow_uids(key, reinterpret_cast<uid_t *>(uids.data()), static_cast<int>(num));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (n <= 0) return env->NewIntArray(0);
    jintArray result = env->NewIntArray(static_cast<jsize>(n));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(n), uids.data());
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeGrantSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint uid, jint to_uid, jstring selinux_context_jstr) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    const char *sctx = nullptr;
    if (selinux_context_jstr) sctx = env->GetStringUTFChars(selinux_context_jstr, nullptr);
    struct su_profile profile {};
    profile.uid = static_cast<unsigned int>(uid);
    profile.to_uid = static_cast<unsigned int>(to_uid);
    if (sctx) {
        strncpy(profile.scontext, sctx, sizeof(profile.scontext) - 1);
        profile.scontext[sizeof(profile.scontext) - 1] = '\0';
        env->ReleaseStringUTFChars(selinux_context_jstr, sctx);
    } else {
        profile.scontext[0] = '\0';
    }
    long rc = sc_su_grant_uid(key, &profile);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return static_cast<jlong>(rc);
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeRevokeSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint uid) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    long rc = sc_su_revoke_uid(key, static_cast<uid_t>(uid));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return static_cast<jlong>(rc);
}

}  // extern "C"
