package com.zhaowen.mapmate;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.amap.api.maps.model.LatLng;

import java.util.Locale;

/** 定位 + 语音；路径匹配与判定全部交给 NavCore。 */
public class Guidance {

    private static final String TAG = "MapMateGuide";
    private static final double OFF_ROUTE_M = 80;
    private static final int OFF_ROUTE_HITS = 3;
    private static final double ARRIVE_M = 60;

    public interface Listener {
        void onGuide(String text);
        void onPosition(LatLng cur);
        void onArrived();
        void onOffRoute(LatLng cur);

        /** 导航进度：剩余全程 / 距下一动作 / 动作描述 */
        default void onProgress(double remainMeters, int nextDistance, String instruction) {}
    }

    private LocationManager lm;
    private LocationListener locListener;
    private TextToSpeech tts;
    private RoutePlanner.Path route;
    private Listener listener;

    private LatLng dest;
    private double[] pts;
    private double[] stepAlong;
    private double routeLength = 0;
    private int lastStep = -1;
    private int lastBucket = -1;
    private boolean arrived = false;
    private int offHits = 0;
    private long lastReplanAt = 0;
    private static final double OFF_ROUTE_GRACE_M = 300;   // 起步 300 米内不判偏航
    private static final long REPLAN_COOLDOWN_MS = 8000;   // 重规划后冷却

    public void start(Context ctx, RoutePlanner.Path r, LatLng dest, Listener l) {
        this.dest = dest;
        stop();
        this.route = r;
        this.listener = l;
        this.lastStep = -1;
        this.lastBucket = -1;
        this.arrived = false;
        this.offHits = 0;
        this.lastReplanAt = System.currentTimeMillis();

        if (r == null || r.points.size() < 2) return;

        pts = new double[r.points.size() * 2];
        for (int i = 0; i < r.points.size(); i++) {
            LatLng p = r.points.get(i);
            pts[i * 2] = p.latitude;
            pts[i * 2 + 1] = p.longitude;
        }

        routeLength = NavCore.length(pts);
        stepAlong = new double[r.steps.size()];
        for (int i = 0; i < r.steps.size(); i++) {
            LatLng m = r.steps.get(i).maneuver;
            stepAlong[i] = m == null ? -1 : NavCore.alongOffset(m.latitude, m.longitude, pts)[0];
        }

        try {
            if (tts == null) {
                tts = new TextToSpeech(ctx, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int res = tts.setLanguage(Locale.CHINA);
                        Log.i(TAG, "tts ready, setLanguage=" + res);
                    } else {
                        Log.w(TAG, "tts init failed: " + status);
                    }
                });
            }

            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            locListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) { onFix(location); }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestLocationUpdates(provider, 1000, 0, locListener);
            Location last = lm.getLastKnownLocation(provider);
            if (last != null) onFix(last);
            Log.i(TAG, "guidance started via " + provider + ", steps=" + r.steps.size());
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }

    public void stop() {
        try { if (lm != null && locListener != null) lm.removeUpdates(locListener); } catch (Throwable ignored) {}
        lm = null;
        locListener = null;
    }

    public void shutdown() {
        stop();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null;
    }

    private void onFix(Location loc) {
        if (loc == null || route == null || pts == null) return;
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        double[] ao = NavCore.alongOffset(lat, lng, pts);
        double along = ao[0], offset = ao[1];
        Log.i(TAG, "fix " + lat + "," + lng + " along=" + (int) along + " off=" + (int) offset);

        if (listener != null) listener.onPosition(new LatLng(lat, lng));

        boolean grace = along < OFF_ROUTE_GRACE_M
                || System.currentTimeMillis() - lastReplanAt < REPLAN_COOLDOWN_MS;
        if (!arrived && !grace && offset > OFF_ROUTE_M) {
            offHits++;
            if (offHits >= OFF_ROUTE_HITS) {
                offHits = 0;
                Log.i(TAG, "off route, replan");
                lastReplanAt = System.currentTimeMillis();
                say("已偏离路线，重新规划");
                if (listener != null) listener.onOffRoute(new LatLng(lat, lng));
                return;
            }
        } else {
            offHits = 0;
        }

        boolean nearDest = dest != null
                && NavCore.distance(lat, lng, dest.latitude, dest.longitude) < ARRIVE_M;
        if (!arrived && nearDest) {
            arrived = true;
            say("到达目的地附近");
            if (listener != null) listener.onArrived();
            return;
        }
        if (arrived) return;

        int next = NavCore.nextIndex(stepAlong, along, 8);
        if (next < 0) {
            if (listener != null) listener.onGuide("即将到达终点");
            return;
        }

        double dist = stepAlong[next] - along;
        String ins = route.steps.get(next).instruction;
        if (listener != null) {
            listener.onProgress(Math.max(0, routeLength - along), (int) dist, ins);
            listener.onGuide(ins + " · " + (int) dist + " 米");
        }

        int bucket = NavCore.bucket(dist);
        if (bucket > 0 && (next != lastStep || bucket < lastBucket)) {
            say("前方" + bucket + "米，" + ins);
            lastStep = next;
            lastBucket = bucket;
        }
    }

    private void say(String s) {
        Log.i(TAG, "speak: " + s);
        try {
            if (tts != null) tts.speak(s, TextToSpeech.QUEUE_ADD, null, "g" + System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }
}