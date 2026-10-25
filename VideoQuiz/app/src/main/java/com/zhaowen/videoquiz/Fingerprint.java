package com.zhaowen.videoquiz;

import android.graphics.Bitmap;

/** 画面指纹：32x64 灰度网格，归一化后比余弦相似度。用于丢弃静止帧。 */
public class Fingerprint {

    private static final int W = 32, H = 64;

    public static float[] of(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, W, H, true);
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        if (small != src) small.recycle();

        float[] v = new float[px.length];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float g = (float) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
            v[i] = g;
            sum += g;
        }
        double mean = sum / v.length;
        double var = 0;
        for (float x : v) var += (x - mean) * (x - mean);
        double std = Math.sqrt(var / v.length) + 1e-6;
        for (int i = 0; i < v.length; i++) v[i] = (float) ((v[i] - mean) / std);
        return v;
    }

    /** 1.0 = 几乎相同；实测：静止帧 1.000，不同页 0.90~0.95 */
    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot / a.length;
    }

    public static final double SAME_FRAME = 0.995;
}