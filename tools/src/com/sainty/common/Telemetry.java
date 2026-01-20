package com.sainty.common;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Telemetry {
    private static final int TIMEOUT_MS = 3000;
    private static final long DEFAULT_INTERVAL_MS = 60_000L;
    private static long lastFlushMs = 0;
    private static long lastRuntimeSeconds = 0;
    private static final Map<String, Long> lastCounterValues = new HashMap<>();

    private Telemetry() {
    }

    // legacy but keep for now, flush better
    public static void tick(
            String scriptName,
            long scriptStartTimeMs,
            long currentCounterValue,
            String counterMetricName
    ) {
        long now = System.currentTimeMillis();

        // Only ONE caller per minute will pass this
        if (!shouldFlush(now)) {
            return;
        }

        long elapsedSeconds = (now - scriptStartTimeMs) / 1000;
        long runtimeDelta = elapsedSeconds - lastRuntimeSeconds;

        if (runtimeDelta > 0) {
            sendMetric(scriptName, "runtime_seconds", runtimeDelta);
            lastRuntimeSeconds = elapsedSeconds;
        }

        long lastValue = lastCounterValues.getOrDefault(counterMetricName, 0L);
        long counterDelta = currentCounterValue - lastValue;

        if (counterDelta > 0) {
            sendMetric(scriptName, counterMetricName, counterDelta);
            lastCounterValues.put(counterMetricName, currentCounterValue);
        }
    }


    public static void sessionStart(String scriptName) {
        sendMetric(scriptName, "session_start", 1);
    }

    public static void sessionEnd(String scriptName) {
        sendMetric(scriptName, "session_end", 1);
    }

    private static void sendMetric(String script, String metric, long value) {
        if (value <= 0) {
            return;
        }
        if (Secrets.STATS_URL == null || Secrets.STATS_URL.isEmpty()) {
            return;
        }
        try {
            String json = String.format(
                    "{\"script\":\"%s\",\"metric\":\"%s\",\"value\":%d}",
                    script, metric, value
            );
            HttpURLConnection conn =
                    (HttpURLConnection) new URL(Secrets.STATS_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", Secrets.STATS_API);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldFlush(long now) {
        if (now - lastFlushMs < DEFAULT_INTERVAL_MS) {
            return false;
        }
        lastFlushMs = now;
        return true;
    }

    public static void flush(
            String scriptName,
            long scriptStartTimeMs,
            Map<String, Long> counters
    ) {
        long now = System.currentTimeMillis();
        if (now - lastFlushMs < DEFAULT_INTERVAL_MS) {
            return;
        }

        long elapsedSeconds = (now - scriptStartTimeMs) / 1000;
        long runtimeDelta = elapsedSeconds - lastRuntimeSeconds;

        if (runtimeDelta > 0) {
            sendMetric(scriptName, "runtime_seconds", runtimeDelta);
            lastRuntimeSeconds = elapsedSeconds;
        }

        for (var entry : counters.entrySet()) {
            long last = lastCounterValues.getOrDefault(entry.getKey(), 0L);
            long delta = entry.getValue() - last;
            if (delta > 0) {
                sendMetric(scriptName, entry.getKey(), delta);
                lastCounterValues.put(entry.getKey(), entry.getValue());
            }
        }

        lastFlushMs = now;
    }


}
