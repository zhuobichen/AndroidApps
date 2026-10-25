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

/** 高德路径规划 v5：驾车（含途经点/车牌/路况）、骑行、步行。 */
public class RoutePlanner {

    private static final String TAG = "MapMateRoute";
    private static final String KEY = "6ecba638325f6307dacbc3385c2a7486";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public enum Mode {
        DRIVE("驾车", "driving"),
        BIKE("骑行", "bicycling"),
        WALK("步行", "walking");

        public final String label;
        public final String path;

        Mode(String label, String path) { this.label = label; this.path = path; }

        public static Mode from(String s) {
            if (s == null) return DRIVE;
            if (s.contains("骑行") || s.contains("自行") || s.contains("bike")) return BIKE;
            if (s.contains("步行") || s.contains("走路") || s.contains("walk")) return WALK;
            return DRIVE;
        }
    }

    public static class Step {
        public String instruction = "";
        public int distance = 0;
        public LatLng maneuver;
    }

    public static class Tmc {
        public String status = "";
        public final List<LatLng> points = new ArrayList<>();
    }

    public static class Path {
        public final List<LatLng> points = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
        public final List<Tmc> tmcs = new ArrayList<>();
        public int distance = 0;
        public int duration = 0;
        public String strategy = "";
        public int tolls = 0;
        public int trafficLights = 0;
        public String restriction = "";
    }

    public static class Route {
        public final List<Path> paths = new ArrayList<>();
        public Mode mode = Mode.DRIVE;
    }

    public interface Callback {
        void onRoute(Route route);
    }

    public static void plan(LatLng from, LatLng to, Callback cb) {
        plan(from, to, Mode.DRIVE, null, null, cb);
    }

    public static void plan(LatLng from, LatLng to, List<LatLng> waypoints, Callback cb) {
        plan(from, to, Mode.DRIVE, waypoints, null, cb);
    }

    public static void plan(LatLng from, LatLng to, Mode mode, List<LatLng> waypoints,
                            String plate, Callback cb) {
        final StringBuilder url = new StringBuilder("https://restapi.amap.com/v5/direction/")
                .append(mode.path)
                .append("?key=").append(KEY)
                .append("&origin=").append(from.longitude).append(",").append(from.latitude)
                .append("&destination=").append(to.longitude).append(",").append(to.latitude);

        if (mode == Mode.DRIVE) {
            url.append("&strategy=10&show_fields=cost,tmcs,navi,polyline");
            if (plate != null && !plate.trim().isEmpty()) {
                url.append("&plate=").append(android.net.Uri.encode(plate.trim()));
            }
        } else {
            url.append("&show_fields=cost,navi,polyline");
        }

        if (waypoints != null && !waypoints.isEmpty()) {
            url.append("&waypoints=");
            for (int i = 0; i < waypoints.size(); i++) {
                if (i > 0) url.append(";");
                url.append(waypoints.get(i).longitude).append(",").append(waypoints.get(i).latitude);
            }
        }

        Log.i(TAG, "request " + mode.path + " waypoints=" + (waypoints == null ? 0 : waypoints.size())
                + " plate=" + (plate == null || plate.trim().isEmpty() ? "(none)" : plate.trim()));
        final Mode m = mode;
        new Thread(() -> {
            Route route = null;
            try {
                Request req = new Request.Builder().url(url.toString()).build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    route = parse(body, m);
                }
            } catch (Throwable t) {
                Log.e(TAG, "plan failed", t);
            }
            final Route r = route;
            if (r != null) Log.i(TAG, "route ok: " + r.mode.label + " " + r.paths.size() + " plans, first="
                    + r.paths.get(0).points.size() + " pts, " + r.paths.get(0).distance + "m");
            else Log.w(TAG, "route failed");
            main.post(() -> cb.onRoute(r));
        }, "route-plan").start();
    }

    private static Route parse(String body, Mode mode) {
        try {
            JSONObject o = new JSONObject(body);
            if (!"1".equals(o.optString("status"))) {
                Log.w(TAG, "api error: " + body);
                return null;
            }
            JSONObject routeObj = o.optJSONObject("route");
            if (routeObj == null) return null;
            JSONArray paths = routeObj.optJSONArray("paths");
            if (paths == null || paths.length() == 0) return null;

            Route r = new Route();
            r.mode = mode;
            for (int pi = 0; pi < paths.length() && pi < 3; pi++) {
                JSONObject path = paths.getJSONObject(pi);
                Path p = new Path();
                p.distance = parseInt(path.optString("distance"));
                p.restriction = path.optString("restriction", "");

                JSONObject cost = path.optJSONObject("cost");
                if (cost != null) {
                    p.duration = parseInt(cost.optString("duration"));
                    p.tolls = parseInt(cost.optString("tolls"));
                    p.trafficLights = parseInt(cost.optString("traffic_lights"));
                } else {
                    p.duration = parseInt(path.optString("duration"));
                }
                p.strategy = "方案" + (pi + 1);

                JSONArray steps = path.optJSONArray("steps");
                if (steps != null) {
                    for (int i = 0; i < steps.length(); i++) {
                        JSONObject s = steps.getJSONObject(i);
                        Step st = new Step();
                        st.instruction = s.optString("instruction", "");
                        st.distance = parseInt(s.optString("step_distance"));

                        String poly = s.optString("polyline", "");
                        if (!poly.isEmpty()) {
                            LatLng last = null;
                            for (String pt : poly.split(";")) {
                                String[] ll = pt.split(",");
                                if (ll.length < 2) continue;
                                LatLng cur = new LatLng(Double.parseDouble(ll[1]), Double.parseDouble(ll[0]));
                                if (p.points.isEmpty() || !same(p.points.get(p.points.size() - 1), cur)) {
                                    p.points.add(cur);
                                }
                                last = cur;
                            }
                            st.maneuver = last;
                        }
                        p.steps.add(st);

                        JSONArray tmcs = s.optJSONArray("tmcs");
                        if (tmcs != null) {
                            for (int t = 0; t < tmcs.length(); t++) {
                                JSONObject to = tmcs.getJSONObject(t);
                                Tmc tmc = new Tmc();
                                tmc.status = to.optString("tmc_status", "");
                                String tp = to.optString("tmc_polyline", "");
                                if (tp.isEmpty()) continue;
                                for (String pt : tp.split(";")) {
                                    String[] ll = pt.split(",");
                                    if (ll.length < 2) continue;
                                    tmc.points.add(new LatLng(Double.parseDouble(ll[1]), Double.parseDouble(ll[0])));
                                }
                                if (tmc.points.size() >= 2) p.tmcs.add(tmc);
                            }
                        }
                    }
                }
                if (p.points.size() > 1) r.paths.add(p);
            }
            return r.paths.isEmpty() ? null : r;
        } catch (Throwable t) {
            Log.e(TAG, "parse failed", t);
            return null;
        }
    }

    private static boolean same(LatLng a, LatLng b) {
        return Math.abs(a.latitude - b.latitude) < 1e-7 && Math.abs(a.longitude - b.longitude) < 1e-7;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}