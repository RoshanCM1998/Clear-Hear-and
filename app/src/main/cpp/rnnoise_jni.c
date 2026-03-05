#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "rnnoise/include/rnnoise.h"

#define LOG_TAG "rnnoise_jni"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// RNNoise requires 480-sample frames at 48kHz (10ms)
#define RNNOISE_FRAME_SIZE 480

/**
 * Initialize RNNoise.
 * Creates a DenoiseState with the default model.
 *
 * @return Pointer to DenoiseState as jlong, or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_clearhearand_audio_RNNoise_init(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;

    DenoiseState* st = rnnoise_create(NULL);  // NULL = use default model
    if (!st) {
        ALOGE("Failed to create RNNoise state");
        return 0;
    }

    ALOGD("RNNoise initialized successfully (frame_size=%d)", RNNOISE_FRAME_SIZE);
    return (jlong)(intptr_t)st;
}

/**
 * Process audio through RNNoise.
 *
 * RNNoise expects exactly 480 samples per call.
 * The input array should contain floats in range [-1.0, 1.0].
 *
 * @param handle Pointer to DenoiseState
 * @param input Float array with exactly 480 samples
 * @return Float array with 480 denoised samples, or NULL on error
 */
JNIEXPORT jfloatArray JNICALL
Java_com_clearhearand_audio_RNNoise_process(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input) {
    (void)thiz;

    if (handle == 0 || input == NULL) {
        ALOGE("Invalid handle or input array");
        return NULL;
    }

    DenoiseState* st = (DenoiseState*)(intptr_t)handle;

    // Get input array length
    jsize inputLen = (*env)->GetArrayLength(env, input);
    if (inputLen != RNNOISE_FRAME_SIZE) {
        ALOGE("Invalid input size: %d (expected %d)", inputLen, RNNOISE_FRAME_SIZE);
        return NULL;
    }

    // Get input samples
    jfloat* inSamples = (*env)->GetFloatArrayElements(env, input, NULL);
    if (!inSamples) {
        ALOGE("Failed to get input array elements");
        return NULL;
    }

    // Allocate output buffer
    float* outSamples = (float*)malloc(RNNOISE_FRAME_SIZE * sizeof(float));
    if (!outSamples) {
        ALOGE("Failed to allocate output buffer");
        (*env)->ReleaseFloatArrayElements(env, input, inSamples, 0);
        return NULL;
    }

    // Process frame through RNNoise
    // Returns VAD probability (0.0 = noise, 1.0 = voice)
    float vad_prob = rnnoise_process_frame(st, outSamples, inSamples);

    // Create output array
    jfloatArray output = (*env)->NewFloatArray(env, RNNOISE_FRAME_SIZE);
    if (!output) {
        ALOGE("Failed to create output array");
        free(outSamples);
        (*env)->ReleaseFloatArrayElements(env, input, inSamples, 0);
        return NULL;
    }

    // Copy processed samples to output
    (*env)->SetFloatArrayRegion(env, output, 0, RNNOISE_FRAME_SIZE, outSamples);

    // Cleanup
    free(outSamples);
    (*env)->ReleaseFloatArrayElements(env, input, inSamples, 0);

    return output;
}

/**
 * Release RNNoise resources.
 *
 * @param handle Pointer to DenoiseState
 */
JNIEXPORT void JNICALL
Java_com_clearhearand_audio_RNNoise_release(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;

    if (handle == 0) return;

    DenoiseState* st = (DenoiseState*)(intptr_t)handle;
    rnnoise_destroy(st);

    ALOGD("RNNoise resources released");
}