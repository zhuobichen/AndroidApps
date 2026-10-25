package com.zhaowen.mapmate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amap.api.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 高程查询：用 OpenTopoData（SRTM 90m）算路线累计爬升。 */
public class Elevation {

    private static final String TAG = "MapMateElevation";
    private static final String URL = "https://api.opentopodata.org/v1/srtm90m?locations=";
    private static final int MAX_POINTS = 100;
    private static final int SAMPLE_METERS = 200;
    private static final long MIN_INTERVAL_MS = 1100;
    private static long lastFetchAt = 0;

    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(int ascent, int minEle, int maxEle);
    }

    /** 沿路线每 200 米取一个点，最多 100 个 */
    public static void ascent(List<LatLng> pts, Callback cb) {
        if (pts == null || pts.size() < 2) { cb.onResult(-1, 0, 0); return; }

        final List<LatLng> samples = new ArrayList<>();
        double acc = 0;
        samples.add(pts.get(0));
        for (int i = 1; i < pts.size() && samples.size() < MAX_POINTS; i++) {
            LatLng a = pts.get(i - 1), b = pts.get(i);
            acc += NavCore.distance(a.latitude, a.longitude, b.latitude, b.longitude);
            if (acc >= SAMPLE_METERS) {
                samples.add(b);
                acc = 0;
            }
        }
        if (samples.size() < 2) { cb.onResult(0, 0, 0); return; }

        new Thread(() -> {
            int ascent = -1, min = 0, max = 0;
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < samples.size(); i++) {
                    if (i > 0) sb.append("|");
                    sb.append(samples.get(i).latitude).append(",").append(samples.get(i).longitude);
                }
                long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFetchAt);
                if (wait > 0) Thread.sleep(wait);
                lastFetchAt = System.currentTimeMillis();

                Request req = new Request.Builder().url(URL + sb).build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONArray arr = new JSONObject(body).optJSONArray("results");
                    if (arr != null && arr.length() > 0) {
                        double prev = arr.getJSONObject(0).optDouble("elevation", 0);
                        double gain = 0;
                        min = (int) prev;
                        max = (int) prev;
                        for (int i = 1; i < arr.length(); i++) {
                            double e = arr.getJSONObject(i).optDouble("elevation", prev);
                            if (e > max) max = (int) e;
                            if (e < min) min = (int) e;
                            double d = e - prev;
                            if (d > 3) gain += d;   // 过滤噪声
                            prev = e;
                        }
                        ascent = (int) Math.round(gain);
                        if (ascent < 5) ascent = 0;   // 平路不报爬升
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "ascent failed: " + t.getMessage());
            }
            final int a = ascent, lo = min, hi = max;
            main.post(() -> cb.onResult(a, lo, hi));
        }, "elevation").start();
    }
}