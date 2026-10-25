package com.zhaowen.mapmate;

import android.content.Context;
import android.util.Log;

import com.amap.api.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 骑行记录：距离 / 时长 / 均速 / 最高速 / 卡路里，本地持久化。 */
public class RideTracker {

    private static final String TAG = "MapMateRide";
    private static final int VERSION = 1;
    /** 骑行约 16 km/h 的 MET 值 */
    private static final double MET = 6.8;
    private static final double DEFAULT_WEIGHT_KG = 65;

    public static class Ride {
        public long startAt = 0;
        public long endAt = 0;
        public double meters = 0;
        public double maxKmh = 0;
        public long movingMs = 0;
        public int ascent = -1;
        public List<LatLng> points = new ArrayList<>();

        public long seconds() { return Math.max(0, (endAt - startAt) / 1000); }

        /** 骑行时间：扣除静止段 */
        public long movingSeconds() {
            return movingMs > 0 ? movingMs / 1000 : seconds();
        }

        public double avgKmh() {
            long s = movingSeconds();
            if (s <= 0) return 0;
            return meters / s * 3.6;
        }
        /** 按均速取 MET，低速和高强度差别明显 */
        public double kcal(double weightKg) {
            double v = avgKmh();
            double met = v < 10 ? 4.0 : v < 16 ? 6.0 : v < 20 ? 8.0 : v < 25 ? 10.0 : 12.0;
            return met * weightKg * movingSeconds() / 3600.0;
        }
    }

    private final File file;
    private final File activeFile;
    private final List<Ride> history = new ArrayList<>();
    private long lastSaveAt = 0;
    private boolean resumed = false;
    private Ride current;
    private LatLng last;
    private long lastAt = 0;
    private double weightKg = DEFAULT_WEIGHT_KG;
    private double instKmh = 0;
    private long movingMs = 0;
    private long lastMoveAt = 0;
    private int movingStreak = 0;

    public RideTracker(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "rides.json");
        this.activeFile = new File(ctx.getFilesDir(), "ride_active.json");
        load();
        loadActive();
    }

    public void setWeight(double kg) { if (kg > 20 && kg < 300) weightKg = kg; }
    public double weight() { return weightKg; }

    public boolean isRiding() { return current != null; }

    /** 本次是恢复出来的（App 被杀/重启前没结束） */
    public boolean resumed() { return resumed; }

    public void start(LatLng at) {
        current = new Ride();
        instKmh = 0;
        lastMoveAt = 0;
        current.startAt = System.currentTimeMillis();
        if (at != null) {
            current.points.add(at);
            last = at;
            lastAt = current.startAt;
        }
        saveActive();
        Log.i(TAG, "ride started");
    }

    /** 每个定位回调调用一次；speedMps < 0 表示系统没给速度 */
    public void update(LatLng p, double speedMps) {
        if (current == null || p == null) return;
        long now = System.currentTimeMillis();
        if (last != null && lastAt > 0) {
            long dt0 = now - lastAt;
            if (dt0 < 800) return;   // 采样太密，忽略

            double d0 = NavCore.distance(last.latitude, last.longitude, p.latitude, p.longitude);
            double kmhSpeed = speedMps > 0.5 ? speedMps * 3.6 : -1;   // 系统给了有效速度就信它
            double kmhDisp = dt0 < 20000 ? d0 / dt0 * 3600 : 0;
            double kmh0 = kmhSpeed > 0 ? kmhSpeed : kmhDisp;

            // 移动判定：有 GPS 速度时看速度；否则看「每次采样位移 ≥ 6 米」
            // （模拟器/部分机型 speed 恒为 0，只能靠位移；抖动通常 < 6 米）
            boolean movingSample = (kmhSpeed > 0) || (d0 >= 6.0);
            if (movingSample) movingStreak++; else movingStreak = 0;
            boolean moving = movingStreak >= 2;

            if (moving) {
                if (d0 >= 1.5 && kmh0 < 75) {
                    current.meters += d0;
                    if (current.points.size() < 20000) current.points.add(p);
                }
                if (kmh0 > current.maxKmh && kmh0 < 75) current.maxKmh = kmh0;
                instKmh = kmh0;
                lastMoveAt = now;
                movingMs += dt0;
                saveActive();
            }
            last = p;
            lastAt = now;
            return;
        }
            double d = NavCore.distance(last.latitude, last.longitude, p.latitude, p.longitude);
            long dt = now - lastAt;
    }

    public Ride stats() {
        if (current == null) return null;
        Ride snapshot = new Ride();
        snapshot.startAt = current.startAt;
        snapshot.endAt = System.currentTimeMillis();
        snapshot.meters = current.meters;
        snapshot.maxKmh = current.maxKmh;
        snapshot.movingMs = movingMs;
        return snapshot;
    }

    public Ride stop() {
        if (current == null) return null;
        current.endAt = System.currentTimeMillis();
        current.movingMs = movingMs;
        Ride done = current;
        current = null;
        last = null;
        lastAt = 0;
        if (done.meters > 50) {
            history.add(0, done);
            while (history.size() > 200) history.remove(history.size() - 1);
            save();
        }
        resumed = false;
        try { if (activeFile.exists()) activeFile.delete(); } catch (Throwable ignored) {}
        Log.i(TAG, "ride stopped: " + Math.round(done.meters) + "m");
        return done;
    }

    public List<Ride> history() { return history; }

    /** 骑行结束后异步补上爬升 */
    public void saveAscent(Ride r, int ascent) {
        if (r == null) return;
        r.ascent = ascent;
        save();
    }

    public double meters() { return current == null ? 0 : current.meters; }
    public long seconds() { return current == null ? 0 : (System.currentTimeMillis() - current.startAt) / 1000; }

    /** 扣除静止后的骑行秒数（与总结口径一致） */
    public long movingSeconds() {
        if (current == null) return 0;
        return movingMs > 0 ? movingMs / 1000 : seconds();
    }
    public double avgKmh() {
        long s = movingMs > 0 ? movingMs / 1000 : seconds();
        return s <= 0 ? 0 : meters() / s * 3.6;
    }
    public double maxKmh() { return current == null ? 0 : current.maxKmh; }

    /** 瞬时速度；15 秒没动就当 0 */
    public double currentKmh() {
        if (current == null) return 0;
        return System.currentTimeMillis() - lastMoveAt > 15000 ? 0 : instKmh;
    }
    public double kcal() {
        double v = avgKmh();
        double met = v < 10 ? 4.0 : v < 16 ? 6.0 : v < 20 ? 8.0 : v < 25 ? 10.0 : 12.0;
        long s = movingMs > 0 ? movingMs / 1000 : seconds();
        return met * weightKg * s / 3600.0;
    }

    // ---------- 进行中骑行持久化 ----------

    /** 每 5 秒存一次内存状态，进程被杀后可恢复 */
    private void saveActive() {
        if (current == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 5000) return;
        lastSaveAt = now;
        try {
            JSONObject o = new JSONObject()
                    .put("startAt", current.startAt)
                    .put("meters", current.meters)
                    .put("maxKmh", current.maxKmh)
                    .put("movingMs", movingMs)
                    .put("lastAt", lastAt);
            JSONArray pts = new JSONArray();
            for (LatLng p : current.points) {
                pts.put(new JSONObject().put("lat", p.latitude).put("lng", p.longitude));
            }
            o.put("points", pts);
            File tmp = new File(activeFile.getParentFile(), "ride_active.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (activeFile.exists()) activeFile.delete();
            if (!tmp.renameTo(activeFile)) {
                Files.copy(tmp.toPath(), activeFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveActive failed: " + t.getMessage());
        }
    }

    private void loadActive() {
        if (!activeFile.exists()) return;
        try {
            String raw = new String(Files.readAllBytes(activeFile.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            long started = o.optLong("startAt", 0);
            if (started <= 0 || System.currentTimeMillis() - started > 12L * 3600 * 1000) {
                activeFile.delete();
                return;
            }
            Ride r = new Ride();
            r.startAt = started;
            r.meters = o.optDouble("meters", 0);
            r.maxKmh = o.optDouble("maxKmh", 0);
            movingMs = o.optLong("movingMs", 0);
            JSONArray pts = o.optJSONArray("points");
            if (pts != null) {
                for (int i = 0; i < pts.length(); i++) {
                    JSONObject p = pts.getJSONObject(i);
                    r.points.add(new LatLng(p.optDouble("lat"), p.optDouble("lng")));
                }
            }
            if (!r.points.isEmpty()) {
                last = r.points.get(r.points.size() - 1);
                lastAt = o.optLong("lastAt", System.currentTimeMillis());
            }
            current = r;
            resumed = true;
            Log.i(TAG, "resumed ride: " + Math.round(r.meters) + "m");
        } catch (Throwable t) {
            Log.w(TAG, "loadActive failed: " + t.getMessage());
            try { activeFile.delete(); } catch (Throwable ignored) {}
        }
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!file.exists()) return;
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            if (o.optInt("version", -1) != VERSION) return;
            JSONArray arr = o.optJSONArray("rides");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                Ride ride = new Ride();
                ride.startAt = r.optLong("startAt");
                ride.endAt = r.optLong("endAt");
                ride.meters = r.optDouble("meters", 0);
                ride.maxKmh = r.optDouble("maxKmh", 0);
                ride.ascent = r.optInt("ascent", -1);
                history.add(ride);
            }
            Log.i(TAG, "history=" + history.size());
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", VERSION);
            JSONArray arr = new JSONArray();
            for (Ride r : history) {
                arr.put(new JSONObject()
                        .put("startAt", r.startAt)
                        .put("endAt", r.endAt)
                        .put("meters", r.meters)
                        .put("maxKmh", r.maxKmh)
                        .put("ascent", r.ascent));
            }
            o.put("rides", arr);
            File tmp = new File(file.getParentFile(), "rides.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (file.exists()) file.delete();
            if (!tmp.renameTo(file)) {
                Files.copy(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.e(TAG, "save failed", t);
        }
    }
}