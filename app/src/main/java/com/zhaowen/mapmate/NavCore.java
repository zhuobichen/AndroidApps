package com.zhaowen.mapmate;

/**
 * 导航核心算法（纯 Java，不依赖 Android），便于在 JVM 上单测。
 * 坐标统一用扁平数组 [lat0, lng0, lat1, lng1, ...]。
 */
public final class NavCore {

    private static final double R = 6371000.0;

    private NavCore() {}

    /** 两点距离（米），haversine。 */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** 折线总长（米）。 */
    public static double length(double[] pts) {
        double acc = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            acc += distance(pts[i], pts[i + 1], pts[i + 2], pts[i + 3]);
        }
        return acc;
    }

    /** 返回 {沿线里程, 横向偏离}。 */
    public static double[] alongOffset(double lat, double lng, double[] pts) {
        double best = Double.MAX_VALUE;
        double bestAlong = 0;
        double acc = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            double aLat = pts[i], aLng = pts[i + 1];
            double bLat = pts[i + 2], bLng = pts[i + 3];
            double seg = distance(aLat, aLng, bLat, bLng);
            double t = project(lat, lng, aLat, aLng, bLat, bLng);
            double pLat = aLat + (bLat - aLat) * t;
            double pLng = aLng + (bLng - aLng) * t;
            double off = distance(lat, lng, pLat, pLng);
            if (off < best) {
                best = off;
                bestAlong = acc + seg * t;
            }
            acc += seg;
        }
        return new double[]{bestAlong, best};
    }

    /** 点到线段的投影比例 [0,1]（平面近似，短距离足够）。 */
    public static double project(double lat, double lng,
                                 double aLat, double aLng, double bLat, double bLng) {
        double dx = bLng - aLng;
        double dy = bLat - aLat;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) return 0;
        double t = ((lng - aLng) * dx + (lat - aLat) * dy) / len2;
        return Math.max(0, Math.min(1, t));
    }

    /** 区间内第一个“前方”的转向点下标；-1 表示没有了。 */
    public static int nextIndex(double[] stepAlong, double along, double aheadMeters) {
        for (int i = 0; i < stepAlong.length; i++) {
            if (stepAlong[i] > along + aheadMeters) return i;
        }
        return -1;
    }

    /** 播报档位：500 / 200 / 50 / 0。 */
    public static int bucket(double distToManeuver) {
        if (distToManeuver > 500) return 500;
        if (distToManeuver > 200) return 200;
        if (distToManeuver > 50) return 50;
        return 0;
    }

    /** 是否到达：距终点直线 < 阈值，或沿线剩余 < 阈值。 */
    public static boolean arrived(double lat, double lng, double[] pts,
                                  double along, double threshold) {
        if (pts.length < 2) return false;
        double endLat = pts[pts.length - 2];
        double endLng = pts[pts.length - 1];
        double toEnd = distance(lat, lng, endLat, endLng);
        double remaining = length(pts) - along;
        return toEnd < threshold || remaining < threshold;
    }
}