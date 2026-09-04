#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include "net.h"
#include "gpu.h"

#define LOG_TAG "AdGuardianYolo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int INPUT_SIZE = 640;
constexpr int ANCHORS = 8400;
constexpr int CHANNELS = 5;
ncnn::Net* g_net = nullptr;
std::string g_backend = "none";

void release_engine() {
    delete g_net;
    g_net = nullptr;
    g_backend = "none";
}

bool try_load(const char* param, const char* bin, bool use_vulkan) {
    release_engine();
    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = use_vulkan;
    g_net->opt.num_threads = 2;
    if (g_net->load_param(param) != 0 || g_net->load_model(bin) != 0) {
        release_engine();
        return false;
    }
    g_backend = use_vulkan ? "gpu" : "cpu";
    return true;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_adguardian_app_vision_YoloNative_nativeInit(
        JNIEnv* env, jclass, jstring jparam, jstring jbin) {
    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);

    int gpu_count = ncnn::get_gpu_count();
    LOGI("ncnn %s vulkan_devices=%d", NCNN_VERSION_STRING, gpu_count);

    bool gpu_usable = false;
    if (gpu_count > 0) {
        const ncnn::GpuInfo& info = ncnn::get_gpu_info(0);
        const char* name = info.device_name();
        gpu_usable = (info.type() == 0 || info.type() == 1)
                && !strcasestr(name, "gfxstream")
                && !strcasestr(name, "swiftshader")
                && !strcasestr(name, "llvmpipe");
        LOGI("vulkan device=%s type=%d usable=%d", name, info.type(), gpu_usable ? 1 : 0);
    }

    bool ok = gpu_usable && try_load(param, bin, true);
    if (!ok) {
        ok = try_load(param, bin, false);
    }

    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    if (!ok) {
        return env->NewStringUTF("error");
    }
    LOGI("backend=%s", g_backend.c_str());
    return env->NewStringUTF(g_backend.c_str());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_adguardian_app_vision_YoloNative_nativeInfer(
        JNIEnv* env, jclass, jobject bitmap) {
    if (g_net == nullptr) {
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    if (info.width != INPUT_SIZE || info.height != INPUT_SIZE
            || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bad bitmap %ux%u format=%d", info.width, info.height, info.format);
        return nullptr;
    }

    ncnn::Mat input = ncnn::Mat::from_android_bitmap(
            env,
            bitmap,
            ncnn::Mat::PIXEL_RGBA2RGB
    );
    if (input.empty()) {
        LOGE("from_android_bitmap failed");
        return nullptr;
    }

    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    input.substract_mean_normalize(nullptr, norm);

    ncnn::Extractor extractor = g_net->create_extractor();
    if (extractor.input("in0", input) != 0) {
        return nullptr;
    }

    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0) {
        LOGE("extract failed");
        return nullptr;
    }
    if (static_cast<int>(output.w * output.h * output.c) != CHANNELS * ANCHORS) {
        LOGE("bad output %dx%dx%d", output.w, output.h, output.c);
        return nullptr;
    }

    ncnn::Mat flat = output.reshape(ANCHORS, CHANNELS);
    std::vector<float> values(static_cast<size_t>(CHANNELS) * ANCHORS);
    for (int row = 0; row < CHANNELS; row++) {
        std::memcpy(
                values.data() + static_cast<size_t>(row) * ANCHORS,
                flat.row(row),
                ANCHORS * sizeof(float)
        );
    }

    jfloatArray result = env->NewFloatArray(CHANNELS * ANCHORS);
    env->SetFloatArrayRegion(result, 0, CHANNELS * ANCHORS, values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_adguardian_app_vision_YoloNative_nativeRelease(JNIEnv*, jclass) {
    release_engine();
    ncnn::destroy_gpu_instance();
}
