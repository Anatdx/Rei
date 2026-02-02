#include "ksu.h"
#include "prelude.h"

#include <jni.h>
#include <linux/capability.h>
#include <pwd.h>
#include <string.h>

NativeBridgeNP(getVersion, jint) {
  uint32_t version = get_version();
  if (version > 0) {
    return (jint)version;
  }
  return legacy_get_info().version;
}

NativeBridgeNP(getAllowList, jintArray) {
  struct ksu_get_allow_list_cmd cmd = {};
  bool result = get_allow_list(&cmd);
  if (!result) {
    return GetEnvironment()->NewIntArray(env, 0);
  }

  jsize array_size = (jsize)cmd.count;
  if (array_size < 0 || (unsigned int)array_size != cmd.count) {
    return GetEnvironment()->NewIntArray(env, 0);
  }

  jintArray array = GetEnvironment()->NewIntArray(env, array_size);
  GetEnvironment()->SetIntArrayRegion(env, array, 0, array_size,
                                      (const jint *)(cmd.uids));
  return array;
}

NativeBridgeNP(isSafeMode, jboolean) { return is_safe_mode(); }
NativeBridgeNP(isLkmMode, jboolean) { return is_lkm_mode(); }
NativeBridgeNP(isManager, jboolean) { return is_manager(); }
NativeBridgeNP(isKsuDriverPresent, jboolean) { return ksu_driver_present(); }
extern int get_last_errno(void);
NativeBridgeNP(getLastErrno, jint) { return get_last_errno(); }

static int getListSize(JNIEnv *env, jobject list) {
  jclass cls = GetEnvironment()->GetObjectClass(env, list);
  jmethodID size = GetEnvironment()->GetMethodID(env, cls, "size", "()I");
  return GetEnvironment()->CallIntMethod(env, list, size);
}

static void fillArrayWithList(JNIEnv *env, jobject list, int *data, int count) {
  jclass cls = GetEnvironment()->GetObjectClass(env, list);
  jmethodID get =
      GetEnvironment()->GetMethodID(env, cls, "get", "(I)Ljava/lang/Object;");
  jclass integerCls = GetEnvironment()->FindClass(env, "java/lang/Integer");
  jmethodID intValue =
      GetEnvironment()->GetMethodID(env, integerCls, "intValue", "()I");
  for (int i = 0; i < count; ++i) {
    jobject integer = GetEnvironment()->CallObjectMethod(env, list, get, i);
    data[i] = GetEnvironment()->CallIntMethod(env, integer, intValue);
  }
}

static uint64_t capListToBits(JNIEnv *env, jobject list) {
  jclass cls = GetEnvironment()->GetObjectClass(env, list);
  jmethodID get =
      GetEnvironment()->GetMethodID(env, cls, "get", "(I)Ljava/lang/Object;");
  jmethodID size = GetEnvironment()->GetMethodID(env, cls, "size", "()I");
  jint listSize = GetEnvironment()->CallIntMethod(env, list, size);
  jclass integerCls = GetEnvironment()->FindClass(env, "java/lang/Integer");
  jmethodID intValue =
      GetEnvironment()->GetMethodID(env, integerCls, "intValue", "()I");
  uint64_t result = 0;
  for (int i = 0; i < listSize; ++i) {
    jobject integer = GetEnvironment()->CallObjectMethod(env, list, get, i);
    int data = GetEnvironment()->CallIntMethod(env, integer, intValue);
    if (cap_valid(data)) {
      result |= (1ULL << data);
    }
  }
  return result;
}

NativeBridge(getAppProfile, jobject, jstring pkg, jint uid) {
  if (!pkg) return NULL;
  if (GetEnvironment()->GetStringLength(env, pkg) > KSU_MAX_PACKAGE_NAME) {
    return NULL;
  }

  char key[KSU_MAX_PACKAGE_NAME] = {0};
  const char *cpkg = GetEnvironment()->GetStringUTFChars(env, pkg, NULL);
  strcpy(key, cpkg);
  GetEnvironment()->ReleaseStringUTFChars(env, pkg, cpkg);

  struct app_profile profile = {0};
  profile.version = KSU_APP_PROFILE_VER;
  strcpy(profile.key, key);
  profile.current_uid = uid;

  bool useDefaultProfile = get_app_profile(&profile) != 0;

  jclass cls = GetEnvironment()->FindClass(env, "com/anatdx/rei/KsuNatives$Profile");
  jmethodID constructor = GetEnvironment()->GetMethodID(env, cls, "<init>", "()V");
  jobject obj = GetEnvironment()->NewObject(env, cls, constructor);

  jfieldID keyField = GetEnvironment()->GetFieldID(env, cls, "name", "Ljava/lang/String;");
  jfieldID currentUidField = GetEnvironment()->GetFieldID(env, cls, "currentUid", "I");
  jfieldID allowSuField = GetEnvironment()->GetFieldID(env, cls, "allowSu", "Z");
  jfieldID nonRootUseDefaultField = GetEnvironment()->GetFieldID(env, cls, "nonRootUseDefault", "Z");

  GetEnvironment()->SetObjectField(env, obj, keyField, GetEnvironment()->NewStringUTF(env, profile.key));
  GetEnvironment()->SetIntField(env, obj, currentUidField, profile.current_uid);

  if (useDefaultProfile) {
    GetEnvironment()->SetBooleanField(env, obj, allowSuField, false);
    GetEnvironment()->SetBooleanField(env, obj, nonRootUseDefaultField, true);
    return obj;
  }

  GetEnvironment()->SetBooleanField(env, obj, allowSuField, profile.allow_su != 0);
  // Other fields are left as defaults on JVM side for now.
  return obj;
}

NativeBridge(setAppProfile, jboolean, jobject profile) {
  if (!profile) return false;

  jclass cls = GetEnvironment()->FindClass(env, "com/anatdx/rei/KsuNatives$Profile");
  jfieldID keyField = GetEnvironment()->GetFieldID(env, cls, "name", "Ljava/lang/String;");
  jfieldID currentUidField = GetEnvironment()->GetFieldID(env, cls, "currentUid", "I");
  jfieldID allowSuField = GetEnvironment()->GetFieldID(env, cls, "allowSu", "Z");
  jfieldID nonRootUseDefaultField =
      GetEnvironment()->GetFieldID(env, cls, "nonRootUseDefault", "Z");

  jobject key = GetEnvironment()->GetObjectField(env, profile, keyField);
  if (!key) return false;
  if (GetEnvironment()->GetStringLength(env, (jstring)key) > KSU_MAX_PACKAGE_NAME) {
    return false;
  }

  const char *cpkg = GetEnvironment()->GetStringUTFChars(env, (jstring)key, NULL);
  char p_key[KSU_MAX_PACKAGE_NAME] = {0};
  strcpy(p_key, cpkg);
  GetEnvironment()->ReleaseStringUTFChars(env, (jstring)key, cpkg);

  jint currentUid = GetEnvironment()->GetIntField(env, profile, currentUidField);
  jboolean allowSu = GetEnvironment()->GetBooleanField(env, profile, allowSuField);
  jboolean nonRootUseDefault =
      GetEnvironment()->GetBooleanField(env, profile, nonRootUseDefaultField);

  struct app_profile p = {0};
  p.version = KSU_APP_PROFILE_VER;
  strcpy(p.key, p_key);
  p.allow_su = allowSu ? 1 : 0;
  p.current_uid = currentUid;
  if (p.allow_su) {
    // Kernel validates root profile (domain must be non-empty, groups_count sane),
    // even when "use_default" is true. Provide a minimal valid root profile
    // that matches YukiSU's default domain.
    p.rp_config.use_default = 1;
    p.rp_config.profile.uid = 0;
    p.rp_config.profile.gid = 0;
    p.rp_config.profile.groups_count = 0;
    p.rp_config.profile.capabilities.effective = UINT64_MAX;
    p.rp_config.profile.capabilities.permitted = UINT64_MAX;
    p.rp_config.profile.capabilities.inheritable = 0;
    strcpy(p.rp_config.profile.selinux_domain, "u:r:su:s0");
    p.rp_config.profile.namespaces = 0;
  } else {
    p.nrp_config.use_default = nonRootUseDefault ? 1 : 0;
    p.nrp_config.profile.umount_modules = 1;
  }

  return set_app_profile(&p);
}

