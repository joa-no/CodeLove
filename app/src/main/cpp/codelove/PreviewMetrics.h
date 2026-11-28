// CodeLove preview-only instrumentation, GPL-3.0-only.
#pragma once
#include <atomic>
#include <cstdint>
#include <jni.h>

namespace codelove_preview {
// Top two bits hold the actual renderer; remaining bits count successful
// active calls to love.graphics.present(). No Java call or lock per frame.
static std::atomic<uint32_t> stats{0};

static void record(uint32_t renderer)
{
    if (renderer == 0) return;
    const uint32_t previous = stats.load(std::memory_order_relaxed);
    stats.store((renderer << 30) | ((previous + 1u) & 0x3fffffffu),
                std::memory_order_relaxed);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_org_love2d_android_GameActivity_nativeFrameStats(JNIEnv *, jclass)
{
    return static_cast<jint>(codelove_preview::stats.load(std::memory_order_relaxed));
}
