package com.zhaowen.mapmate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 高德 Web 服务：POI 搜索优先，地理编码兜底。 */
public class Geocoder {

    private static final String TAG = "MapMateGeo";
    private static final String KEY = "6ecba638325f6307dacbc3385c2a7486";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(double lat, double lng, String name);
    }

    public interface ReverseCallback {
        void onAddress(String address);
    }

    public static class Poi {
        public String name = "";
        public String address = "";
        public double lat, lng;
        public int distance = 0;
        public String rating = "";
        public String cost = "";
        public String openTime = "";
    }

    public interface SearchCallback {
        void onResult(java.util.List<Poi> list);
    }

    /** 关键字搜地点：优先按当前位置排序 */
    public static void search(final String keyword, final double biasLat, final double biasLng,
                              final SearchCallback cb) {
        new Thread(() -> {
            java.util.List<Poi> out = new java.util.ArrayList<>();
            try {
                String bias = Double.isNaN(biasLat) ? ""
                        : "&location=" + biasLng + "," + biasLat + "&sortrule=distance";
                String url = "https://restapi.amap.com/v3/place/text?keywords=" + enc(keyword)
                        + "&key=" + KEY + "&offset=10&page=1&extensions=all" + bias;
                Request req = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONObject o = new JSONObject(body);
                    JSONArray pois = o.optJSONArray("pois");
                    if (pois != null) {
                        for (int i = 0; i < pois.length(); i++) {
                            JSONObject p = pois.getJSONObject(i);
                            String loc = p.optString("location", "");
                            if (loc.isEmpty()) continue;
                            String[] ll = loc.split(",");
                            Poi poi = new Poi();
                            poi.name = p.optString("name", "");
                            poi.address = p.optString("address", "");
                            poi.lng = Double.parseDouble(ll[0]);
                            poi.lat = Double.parseDouble(ll[1]);
                            poi.distance = (int) parseD(p.optString("distance", "0"));
                            JSONObject biz = p.optJSONObject("biz_ext");
                            if (biz != null) {
                                poi.rating = biz.optString("rating", "");
                                poi.cost = biz.optString("cost", "");
                                poi.openTime = biz.optString("opentime2", biz.optString("open_time", ""));
                            }
                            out.add(poi);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "search failed", t);
            }
            final java.util.List<Poi> r = out;
            main.post(() -> cb.onResult(r));
        }, "poi-search").start();
    }

    /** 周边搜索：以当前位置为圆心，按分类找附近 POI */
    public static void nearby(final String keyword, final double lat, final double lng,
                              final int radius, final SearchCallback cb) {
        new Thread(() -> {
            java.util.List<Poi> out = new java.util.ArrayList<>();
            try {
                String url = "https://restapi.amap.com/v3/place/around?keywords=" + enc(keyword)
                        + "&location=" + lng + "," + lat + "&radius=" + radius
                        + "&key=" + KEY + "&offset=10&page=1&extensions=all&sortrule=distance";
                Request req = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONObject o = new JSONObject(body);
                    JSONArray pois = o.optJSONArray("pois");
                    if (pois != null) {
                        for (int i = 0; i < pois.length(); i++) {
                            JSONObject p = pois.getJSONObject(i);
                            String loc = p.optString("location", "");
                            if (loc.isEmpty()) continue;
                            String[] ll = loc.split(",");
                            Poi poi = new Poi();
                            poi.name = p.optString("name", "");
                            poi.address = p.optString("address", "");
                            poi.lng = Double.parseDouble(ll[0]);
                            poi.lat = Double.parseDouble(ll[1]);
                            poi.distance = (int) parseD(p.optString("distance", "0"));
                            JSONObject biz = p.optJSONObject("biz_ext");
                            if (biz != null) {
                                poi.rating = biz.optString("rating", "");
                                poi.cost = biz.optString("cost", "");
                                poi.openTime = biz.optString("opentime2", biz.optString("open_time", ""));
                            }
                            out.add(poi);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "nearby failed", t);
            }
            final java.util.List<Poi> r = out;
            main.post(() -> cb.onResult(r));
        }, "poi-nearby").start();
    }

    private static double parseD(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /** 逆地理：坐标 → 地址 */
    public static void reverse(final double lat, final double lng, final ReverseCallback cb) {
        new Thread(() -> {
            String addr = "";
            try {
                String url = "https://restapi.amap.com/v3/geocode/regeo?location="
                        + lng + "," + lat + "&key=" + KEY;
                Request req = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONObject o = new JSONObject(body);
                    JSONObject re = o.optJSONObject("regeocode");
                    if (re != null) addr = re.optString("formatted_address", "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "reverse failed", t);
            }
            final String a = addr;
            main.post(() -> cb.onAddress(a));
        }, "regeo").start();
    }

    public static void resolve(final String place, final Callback cb) {
        resolve(place, Double.NaN, Double.NaN, cb);
    }

    /** biasLat/biasLng：当前地图中心，用来把同名地点拉回用户附近。 */
    public static void resolve(final String place, final double biasLat, final double biasLng, final Callback cb) {
        new Thread(() -> {
            double[] ll = null;
            try {
                String bias = "";
                if (!Double.isNaN(biasLat)) {
                    bias = "&location=" + biasLng + "," + biasLat + "&sortrule=distance";
                }
                ll = query("https://restapi.amap.com/v3/place/text?keywords=" + enc(place)
                        + "&key=" + KEY + "&offset=1&page=1" + bias, "pois");
            } catch (Throwable t) {
                Log.w(TAG, "poi failed", t);
            }
            if (ll == null) {
                try {
                    ll = query("https://restapi.amap.com/v3/geocode/geo?address=" + enc(place)
                            + "&key=" + KEY, "geocodes");
                } catch (Throwable t) {
                    Log.w(TAG, "geo failed", t);
                }
            }
            final double[] r = ll;
            main.post(() -> {
                if (r != null) cb.onResult(r[0], r[1], place);
                else cb.onResult(Double.NaN, Double.NaN, place);
            });
        }, "geocode").start();
    }

    private static double[] query(String url, String arrField) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.optJSONArray(arrField);
                if (arr == null || arr.length() == 0) return null;
                String loc = arr.getJSONObject(0).optString("location", "");
                if (loc.isEmpty()) return null;
                String[] p = loc.split(",");
                return new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[0])};
            }
        } catch (Throwable t) {
            Log.w(TAG, "query failed: " + url, t);
            return null;
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}