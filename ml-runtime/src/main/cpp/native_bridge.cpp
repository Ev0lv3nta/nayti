#include <jni.h>

#include "runtime_contract.hpp"

extern "C" JNIEXPORT jint JNICALL
Java_app_nayti_ml_runtime_NativeRuntime_contractVersion(JNIEnv*, jobject) {
    return static_cast<jint>(nayti::ml::runtime_contract_version());
}
