package com.zhaowen.videoquiz;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/** 把一帧切成若干重叠的横条并放大，让模型看清小字。 */
public class FrameTiler {

    public static List<Bitmap> split(Bitmap src, int parts, float overlap, int targetWidth) {
        List<Bitmap> out = new ArrayList<>();
        int h = src.getHeight(), w = src.getWidth();
        if (h < 200) { out.add(scale(src, targetWidth)); return out; }
        float span = 1f / parts;
        for (int i = 0; i < parts; i++) {
            float top = Math.max(0f, i * span - overlap / 2f);
            float bottom = Math.min(1f, (i + 1) * span + overlap / 2f);
            int y0 = (int) (top * h), y1 = (int) (bottom * h);
            if (y1 - y0 < 80) continue;
            Bitmap tile = Bitmap.createBitmap(src, 0, y0, w, y1 - y0);
            out.add(scale(tile, targetWidth));
        }
        return out;
    }

    private static Bitmap scale(Bitmap b, int targetWidth) {
        if (b.getWidth() >= targetWidth) return b;
        float k = (float) targetWidth / b.getWidth();
        Bitmap out = Bitmap.createBitmap(targetWidth, Math.round(b.getHeight() * k), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()),
                new Rect(0, 0, out.getWidth(), out.getHeight()), p);
        if (out != b) b.recycle();
        return out;
    }
}