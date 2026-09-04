package org.adguardian.app.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.adguardian.app.debug.DebugLogStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class YoloSkipDetector {
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean found;
        public final Rect bounds;
        public final String backend;
        public final float confidence;

        private Result(boolean found, Rect bounds, String backend, float confidence) {
            this.found = found;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.backend = backend;
            this.confidence = confidence;
        }

        public static Result none(String backend) {
            return new Result(false, null, backend, 0f);
        }

        public static Result found(Rect bounds, String backend, float confidence) {
            return new Result(true, bounds, backend, confidence);
        }
    }

    private static final String PARAM_ASSET = "yolo.ncnn.param";
    private static final String BIN_ASSET = "yolo.ncnn.bin";
    private static final int INPUT_SIZE = 640;
    private static final int ANCHORS = 8400;
    private static final float CONFIDENCE_THRESHOLD = 0.55f;
    private static final float BRAND_ZONE_TOP = 0.80f;
    private static final float BRAND_ZONE_LEFT = 0.25f;
    private static final float BRAND_ZONE_RIGHT = 0.75f;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "adguardian-yolo"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private boolean initAttempted;
    private boolean initOk;
    private String backend = "uninitialized";

    public YoloSkipDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void warmUp() {
        executor.execute(this::ensureInitialized);
    }

    public boolean analyze(Bitmap bitmap, Callback callback) {
        if (bitmap == null || callback == null || !busy.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(() -> {
            Result result;
            try {
                result = detect(bitmap);
            } catch (RuntimeException exception) {
                DebugLogStore.error(context, "L3_YOLO_ERROR", "backend=" + backend, exception);
                result = Result.none(backend);
            }
            Result finalResult = result;
            mainHandler.post(() -> {
                busy.set(false);
                callback.onResult(finalResult);
            });
        });
        return true;
    }

    public void close() {
        executor.execute(() -> {
            if (initOk && YoloNative.ensureLoaded()) {
                YoloNative.nativeRelease();
            }
        });
        executor.shutdown();
    }

    private Result detect(Bitmap source) {
        if (!ensureInitialized()) {
            return Result.none(backend);
        }

        float scale = INPUT_SIZE / (float) Math.max(source.getWidth(), source.getHeight());
        int scaledWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, Math.round(source.getHeight() * scale));
        float padX = (INPUT_SIZE - scaledWidth) / 2f;
        float padY = (INPUT_SIZE - scaledHeight) / 2f;

        Bitmap input = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        Canvas canvas = new Canvas(input);
        canvas.drawColor(Color.rgb(114, 114, 114));
        canvas.drawBitmap(scaled, padX, padY, null);
        scaled.recycle();

        float[] output;
        try {
            output = YoloNative.nativeInfer(input);
        } finally {
            input.recycle();
        }
        if (output == null || output.length < 5 * ANCHORS) {
            return Result.none(backend);
        }

        int scoreOffset = 4 * ANCHORS;
        int bestIndex = -1;
        float bestScore = -1f;
        for (int i = 0; i < ANCHORS; i++) {
            float score = output[scoreOffset + i];
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestIndex < 0 || bestScore < CONFIDENCE_THRESHOLD) {
            return Result.none(backend);
        }

        float centerX = (output[bestIndex] - padX) / scale;
        float centerY = (output[ANCHORS + bestIndex] - padY) / scale;
        float width = output[2 * ANCHORS + bestIndex] / scale;
        float height = output[3 * ANCHORS + bestIndex] / scale;

        if (centerY > source.getHeight() * BRAND_ZONE_TOP
                && centerX > source.getWidth() * BRAND_ZONE_LEFT
                && centerX < source.getWidth() * BRAND_ZONE_RIGHT) {
            return Result.none(backend);
        }

        Rect bounds = new Rect(
                clamp(Math.round(centerX - width / 2f), 0, source.getWidth()),
                clamp(Math.round(centerY - height / 2f), 0, source.getHeight()),
                clamp(Math.round(centerX + width / 2f), 0, source.getWidth()),
                clamp(Math.round(centerY + height / 2f), 0, source.getHeight())
        );
        if (!safeBounds(bounds, source.getWidth(), source.getHeight())) {
            return Result.none(backend);
        }
        return Result.found(bounds, backend, bestScore);
    }

    private boolean ensureInitialized() {
        if (initAttempted) {
            return initOk;
        }
        initAttempted = true;
        if (!YoloNative.ensureLoaded()) {
            backend = "native-unavailable";
            return false;
        }
        File param = extractAsset(PARAM_ASSET);
        File bin = extractAsset(BIN_ASSET);
        if (param == null || bin == null) {
            backend = "model-unavailable";
            return false;
        }
        String selected = YoloNative.nativeInit(param.getAbsolutePath(), bin.getAbsolutePath());
        backend = selected == null ? "error" : selected;
        initOk = !"error".equals(backend);
        DebugLogStore.info(context, "L3_YOLO_READY", "backend=" + backend);
        return initOk;
    }

    private File extractAsset(String name) {
        File directory = new File(context.getFilesDir(), "vision");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File destination = new File(directory, name);
        try (InputStream input = context.getAssets().open(name)) {
            if (destination.isFile() && destination.length() > 0L) {
                return destination;
            }
            try (FileOutputStream output = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            return destination;
        } catch (Exception exception) {
            DebugLogStore.error(context, "L3_MODEL_EXTRACT_ERROR", "asset=" + name, exception);
            return null;
        }
    }

    private boolean safeBounds(Rect bounds, int screenWidth, int screenHeight) {
        if (bounds.width() <= 0 || bounds.height() <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return false;
        }
        long area = (long) bounds.width() * bounds.height();
        long screenArea = (long) screenWidth * screenHeight;
        if (area * 3L >= screenArea) {
            return false;
        }
        return bounds.width() < screenWidth * 0.60f && bounds.height() < screenHeight * 0.30f;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
