package org.adguardian.app.vision;

import android.graphics.Bitmap;

final class YoloNative {
    private static boolean loaded;

    private YoloNative() {
    }

    static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary("yolo_jni");
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError error) {
            return false;
        }
    }

    static native String nativeInit(String paramPath, String binPath);

    static native float[] nativeInfer(Bitmap bitmap);

    static native void nativeRelease();
}
