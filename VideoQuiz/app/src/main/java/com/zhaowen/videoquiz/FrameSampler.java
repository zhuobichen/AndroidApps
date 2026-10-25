package com.zhaowen.videoquiz;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

/** 从视频流式抽帧：逐帧回调，调用方用完自行 recycle。 */
public class FrameSampler {

    public interface Sink {
        /** @return false 表示不再需要后续帧 */
        boolean onFrame(Bitmap bmp, long timeMs, double sharpness, int index, int total);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    /** 清晰度低于该值的帧基本不可读（实测 235 全糊，800 以下多为抖动帧） */
    public static final double MIN_SHARPNESS = 300;

    public static int sample(Context ctx, Uri uri, int fps, int maxFrames,
                             Sink sink, Progress cb) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        int kept = 0;
        try {
            try {
                r.setDataSource(ctx, uri);
            } catch (Throwable e) {
                r.setDataSource(uri.getPath());
            }
            String durStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durStr == null ? 0 : Long.parseLong(durStr);
            if (durationMs <= 0) throw new Exception("读不到视频时长");

            int step = Math.max(200, 1000 / Math.max(1, fps));
            int total = (int) (durationMs / step);
            if (total > maxFrames) {
                step = (int) (durationMs / maxFrames);
                total = maxFrames;
            }

            for (int i = 0; i < total; i++) {
                Bitmap bmp = r.getFrameAtTime((long) i * step * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (bmp != null) {
                    double sharp = laplacianVariance(bmp);
                    if (sharp >= MIN_SHARPNESS) {
                        kept++;
                        if (!sink.onFrame(bmp, (long) i * step, sharp, kept, total)) {
                            bmp.recycle();
                            break;
                        }
                    } else {
                        bmp.recycle();
                    }
                }
                if (cb != null) cb.onProgress(i + 1, total);
            }
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
        return kept;
    }

    /** 缩到 320 宽灰度图后算拉普拉斯方差，越大越清晰 */
    public static double laplacianVariance(Bitmap src) {
        int w = 320;
        int h = Math.max(1, src.getHeight() * w / Math.max(1, src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();

        double[] g = new double[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
        }
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double v = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w];
                sum += v;
                sum2 += v * v;
                n++;
            }
        }
        if (n == 0) return 0;
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }
}