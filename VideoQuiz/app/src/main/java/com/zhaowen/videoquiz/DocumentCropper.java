package com.zhaowen.videoquiz;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** 自动裁掉界面元素与空白边距，只留文字区域，再由 FrameTiler 切片放大。 */
public class DocumentCropper {

    /** 返回文字区域的上下边界（比例 0-1）；找不到返回 null */
    public static float[] textBand(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int step = Math.max(1, w / 240);
        int[] energy = new int[h];
        int prev = -1;
        for (int y = 0; y < h; y++) {
            int sum = 0, cnt = 0, last = -1;
            for (int x = 0; x < w; x += step) {
                int p = src.getPixel(x, y) & 0xFF;
                if (last >= 0) sum += Math.abs(p - last);
                last = p;
                cnt++;
            }
            energy[y] = cnt > 0 ? sum / cnt : 0;
        }
        int max = 0;
        for (int e : energy) max = Math.max(max, e);
        if (max < 8) return null;
        int thr = Math.max(6, max / 5);

        int bestStart = -1, bestEnd = -1, curStart = -1, gaps = 0;
        for (int y = 0; y < h; y++) {
            boolean on = energy[y] >= thr;
            if (on) {
                if (curStart < 0) curStart = y;
                gaps = 0;
            } else if (curStart >= 0) {
                gaps++;
                // 允许中间有小空隙（行间距）
                if (gaps > h / 40) {
                    if (y - gaps - curStart > bestEnd - bestStart) {
                        bestStart = curStart;
                        bestEnd = y - gaps;
                    }
                    curStart = -1;
                    gaps = 0;
                }
            }
        }
        if (curStart >= 0 && h - curStart > bestEnd - bestStart) {
            bestStart = curStart;
            bestEnd = h;
        }
        if (bestStart < 0 || bestEnd - bestStart < h / 8) return null;
        return new float[]{Math.max(0f, (bestStart - 8f) / h), Math.min(1f, (bestEnd + 8f) / h)};
    }

    public static Bitmap crop(Bitmap src) {
        float[] band = textBand(src);
        if (band == null) return src;
        int y0 = (int) (band[0] * src.getHeight());
        int y1 = (int) (band[1] * src.getHeight());
        if (y1 - y0 < 100) return src;
        return Bitmap.createBitmap(src, 0, y0, src.getWidth(), y1 - y0);
    }
}