package com.zhaowen.mapmate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 高德天气：先逆地理拿 adcode，再查实况。 */
public class Weather {

    private static final String TAG = "MapMateWeather";
    private static final String KEY = "6ecba638325f6307dacbc3385c2a7486";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** text 为空表示查不到 */
        void onWeather(String text, String city);
    }

    public static void now(final double lat, final double lng, final Callback cb) {
        new Thread(() -> {
            String city = "";
            String text = "";
            try {
                String regeo = "https://restapi.amap.com/v3/geocode/regeo?location="
                        + lng + "," + lat + "&key=" + KEY;
                JSONObject o = get(regeo);
                JSONObject re = o.optJSONObject("regeocode");
                String adcode = "";
                if (re != null) {
                    JSONObject ac = re.optJSONObject("addressComponent");
                    if (ac != null) {
                        adcode = ac.optString("adcode", "");
                        city = ac.optString("city", ac.optString("province", ""));
                    }
                }
                if (adcode.isEmpty()) { cb.onWeather("", city); return; }

                String w = "https://restapi.amap.com/v3/weather/weatherInfo?key=" + KEY
                        + "&city=" + adcode + "&extensions=base";
                JSONObject wo = get(w);
                JSONArray lives = wo.optJSONArray("lives");
                if (lives != null && lives.length() > 0) {
                    JSONObject live = lives.getJSONObject(0);
                    text = live.optString("weather", "") + " "
                            + live.optString("temperature", "") + "℃，"
                            + live.optString("winddirection", "") + "风 "
                            + live.optString("windpower", "") + " 级";
                    if (city.isEmpty()) city = live.optString("city", "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "failed: " + t.getMessage());
            }
            final String t2 = text, c2 = city;
            main.post(() -> cb.onWeather(t2, c2));
        }, "weather").start();
    }

    private static JSONObject get(String url) throws Exception {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            return new JSONObject(body);
        }
    }
}