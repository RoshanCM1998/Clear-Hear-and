#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "rnnoise_jni"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Simple handle struct; in a real integration, store RNNoise state here.
typedef struct {
    int placeholder;
} RNHandle;

JNIEXPORT jlong JNICALL
Java_com_1example_1audio_RNNoise_init(JNIEnv* env, jobject thiz) {
    RNHandle* h = (RNHandle*)calloc(1, sizeof(RNHandle));
    if (!h) return 0;
    h->placeholder = 1;
    ALOGD("RNNoise init stub");
    return (jlong)(intptr_t)h;
}

JNIEXPORT jfloatArray JNICALL
Java_com_1example_1audio_RNNoise_process(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input) {
    (void)thiz; (void)handle;
    if (input == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, input);
    jfloat* in = (*env)->GetFloatArrayElements(env, input, NULL);
    if (!in) return NULL;
    jfloatArray out = (*env)->NewFloatArray(env, n);
    if (!out) {
        (*env)->ReleaseFloatArrayElements(env, input, in, 0);
        return NULL;
    }
    // Pass-through copy; replace with real RNNoise processing later
    (*env)->SetFloatArrayRegion(env, out, 0, n, in);
    (*env)->ReleaseFloatArrayElements(env, input, in, 0);
    return out;
}

JNIEXPORT void JNICALL
Java_com_1example_1audio_RNNoise_release(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    RNHandle* h = (RNHandle*)(intptr_t)handle;
    if (h) {
        free(h);
    }
}