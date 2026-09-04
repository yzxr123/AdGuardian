package org.adguardian.app.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OcrSkipDetector {
    public interface Callback {
        void onResult(Result result);
        void onError(Exception error);
    }

    public static final class Result {
        public final boolean targetFound;
        public final float x;
        public final float y;
        public final String evidence;
        public final String text;
        public final boolean adEvidence;
        public final Integer countdown;
        public final Rect bounds;

        private Result(
                boolean targetFound,
                float x,
                float y,
                String evidence,
                String text,
                boolean adEvidence,
                Integer countdown,
                Rect bounds
        ) {
            this.targetFound = targetFound;
            this.x = x;
            this.y = y;
            this.evidence = evidence;
            this.text = text;
            this.adEvidence = adEvidence;
            this.countdown = countdown;
            this.bounds = bounds;
        }

        public static Result none(boolean adEvidence, Integer countdown) {
            return new Result(false, 0f, 0f, "", "", adEvidence, countdown, null);
        }

        public static Result target(
                String evidence,
                String text,
                Rect bounds,
                boolean adEvidence,
                Integer countdown
        ) {
            return new Result(
                    true,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    evidence,
                    text,
                    adEvidence,
                    countdown,
                    new Rect(bounds)
            );
        }
    }

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private TextRecognizer recognizer;
    private boolean disabled;

    public boolean isBusy() {
        return busy.get();
    }

    public boolean analyze(Bitmap bitmap, Callback callback) {
        if (disabled || bitmap == null || callback == null || !busy.compareAndSet(false, true)) {
            return false;
        }

        TextRecognizer client = recognizer();
        if (client == null) {
            busy.set(false);
            callback.onError(new IllegalStateException("ML Kit OCR unavailable"));
            return true;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        client.process(image)
                .addOnSuccessListener(text -> {
                    busy.set(false);
                    callback.onResult(evaluate(text, width, height));
                })
                .addOnFailureListener(error -> {
                    busy.set(false);
                    callback.onError(error);
                });
        return true;
    }

    public void close() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    private TextRecognizer recognizer() {
        if (disabled) {
            return null;
        }
        if (recognizer != null) {
            return recognizer;
        }
        try {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );
            return recognizer;
        } catch (RuntimeException exception) {
            disabled = true;
            return null;
        }
    }

    private Result evaluate(Text text, int screenWidth, int screenHeight) {
        boolean adEvidence = false;
        boolean onboardingEvidence = false;
        Integer countdown = null;
        Candidate best = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = normalize(line.getText());
                Rect bounds = line.getBoundingBox();
                if (value.isEmpty() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                    continue;
                }

                if (containsAdLabel(value)) {
                    adEvidence = true;
                }
                if (looksLikeOnboarding(value)) {
                    onboardingEvidence = true;
                }

                Integer parsedCountdown = parseCountdown(value);
                if (parsedCountdown != null && isCornerLike(bounds, screenWidth, screenHeight)) {
                    countdown = parsedCountdown;
                }

                String evidence = classifySkipText(value);
                if (evidence == null || looksLikeNonAdFlow(value)) {
                    continue;
                }
                if (!safeTargetBounds(bounds, screenWidth, screenHeight, evidence)) {
                    continue;
                }

                int score = targetScore(value, bounds, screenWidth, screenHeight, evidence);
                if (best == null || score > best.score) {
                    best = new Candidate(evidence, value, bounds, score);
                }
            }
        }

        if (best == null) {
            return Result.none(adEvidence, countdown);
        }
        if (onboardingEvidence && !"ocr-explicit-ad-action".equals(best.evidence)) {
            return Result.none(adEvidence, countdown);
        }
        return Result.target(best.evidence, best.text, best.bounds, adEvidence, countdown);
    }

    private String classifySkipText(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.length() > 20) {
            return null;
        }
        if (value.contains("关闭广告") || value.contains("跳过广告") || value.contains("关闭推广")) {
            return "ocr-explicit-ad-action";
        }
        if (value.contains("跳过") || value.contains("跳過") || value.contains("跳 过") || value.contains("跳 過")) {
            return "ocr-skip";
        }
        if (lower.matches(".*\\b(skip|skip ad|close ad|dismiss ad)\\b.*")) {
            return "ocr-skip-en";
        }
        return null;
    }

    private boolean safeTargetBounds(Rect bounds, int width, int height, String evidence) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        long area = (long) bounds.width() * (long) bounds.height();
        long screenArea = (long) width * (long) height;
        if (area <= 0 || area * 3L >= screenArea) {
            return false;
        }
        if (bounds.width() >= width * 0.60f || bounds.height() >= height * 0.22f) {
            return false;
        }

        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        boolean corner = cx <= width * 0.38f || cx >= width * 0.62f;
        boolean upper = cy <= height * 0.52f;

        if ("ocr-explicit-ad-action".equals(evidence)) {
            return cy <= height * 0.82f;
        }
        return corner && upper;
    }

    private boolean isCornerLike(Rect bounds, int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        return (cx <= width * 0.38f || cx >= width * 0.62f) && cy <= height * 0.55f;
    }

    private int targetScore(String value, Rect bounds, int width, int height, String evidence) {
        int score = 0;
        String lower = value.toLowerCase(Locale.ROOT);
        if ("ocr-explicit-ad-action".equals(evidence)) {
            score += 70;
        }
        if (value.contains("广告") || lower.contains("ad")) {
            score += 50;
        }
        if (value.contains("跳过") || value.contains("跳過") || lower.contains("skip")) {
            score += 30;
        }
        if (bounds.exactCenterX() >= width * 0.65f) {
            score += 20;
        }
        if (bounds.exactCenterY() <= height * 0.35f) {
            score += 20;
        }
        score -= Math.min(30, value.length());
        return score;
    }

    private boolean containsAdLabel(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.equals("广告")
                || value.contains("广告")
                || value.contains("推广")
                || value.contains("赞助")
                || lower.contains("advertisement")
                || lower.contains("sponsored");
    }

    private Integer parseCountdown(String value) {
        String compact = value.replaceAll("\\s+", "");
        String digits = compact.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 2) {
            return null;
        }
        if (!(compact.contains("秒")
                || compact.contains("s")
                || compact.contains("S")
                || compact.matches("^[0-9]{1,2}$"))) {
            return null;
        }
        try {
            int number = Integer.parseInt(digits);
            return number >= 0 && number <= 60 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean looksLikeOnboarding(String value) {
        return value.contains("下一步")
                || value.contains("选择偏好")
                || value.contains("选择兴趣")
                || value.contains("阅读并同意")
                || value.contains("权限设置")
                || value.contains("开始使用");
    }

    private boolean looksLikeNonAdFlow(String value) {
        return value.contains("教程")
                || value.contains("引导")
                || value.contains("片头")
                || value.contains("片尾")
                || value.contains("视频")
                || value.contains("帮助");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replace('\n', ' ');
    }

    private static final class Candidate {
        final String evidence;
        final String text;
        final Rect bounds;
        final int score;

        Candidate(String evidence, String text, Rect bounds, int score) {
            this.evidence = evidence;
            this.text = text;
            this.bounds = new Rect(bounds);
            this.score = score;
        }
    }
}
