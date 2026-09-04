package org.adguardian.app.debug;

import android.content.Context;
import android.util.Log;

import org.adguardian.app.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugLogStore {
    private static final String TAG = "AdGuardianDebug";
    private static final String FILE_NAME = "adguardian-debug.log";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final int MAX_READ_BYTES = 128 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private DebugLogStore() {
    }

    public static void info(Context context, String code, String message) {
        write(context, "I", code, message, null);
    }

    public static void miss(Context context, String code, String message) {
        write(context, "M", code, message, null);
    }

    public static void success(Context context, String code, String message) {
        write(context, "S", code, message, null);
    }

    public static void error(Context context, String code, String message, Throwable throwable) {
        write(context, "E", code, message, throwable);
    }

    public static String read(Context context) {
        if (!BuildConfig.DEBUG) {
            return "正式版不保存测试日志";
        }
        synchronized (LOCK) {
            File file = logFile(context);
            if (!file.isFile() || file.length() == 0L) {
                return "暂无日志";
            }
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > MAX_READ_BYTES) {
                        byte[] all = output.toByteArray();
                        output.reset();
                        output.write(all, all.length - MAX_READ_BYTES, MAX_READ_BYTES);
                    }
                }
                return output.toString(StandardCharsets.UTF_8.name());
            } catch (Exception exception) {
                Log.e(TAG, "Failed to read local debug log", exception);
                return "读取日志失败  " + exception.getClass().getSimpleName() + "  " + safe(exception.getMessage());
            }
        }
    }

    public static void clear(Context context) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        synchronized (LOCK) {
            File file = logFile(context);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to delete debug log");
            }
        }
    }

    private static void write(
            Context context,
            String level,
            String code,
            String message,
            Throwable throwable
    ) {
        if (!BuildConfig.DEBUG || context == null) {
            return;
        }
        String line = CLOCK.format(new Date())
                + " [" + level + "] "
                + code
                + "  "
                + safe(message);
        if (throwable != null) {
            line += "\n" + stackTrace(throwable);
        }
        Log.d(TAG, line);
        synchronized (LOCK) {
            File file = logFile(context);
            try {
                if (file.length() >= MAX_BYTES) {
                    try (FileOutputStream reset = new FileOutputStream(file, false)) {
                        reset.write((CLOCK.format(new Date()) + " [I] LOG_ROTATED  old test log cleared\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception exception) {
                Log.e(TAG, "Failed to write local debug log", exception);
            }
        }
    }

    private static File logFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String value = writer.toString();
        return value.length() <= 4096 ? value : value.substring(0, 4096) + "\n...stack truncated";
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
