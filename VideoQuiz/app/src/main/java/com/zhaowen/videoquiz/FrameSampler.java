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

    /** 画面平均灰度差达到该值即认为「内容整体换了位置」，立刻取帧（应对快速平移） */
    private static final double MOTION_ACCEPT = 12.0;
    /** 静止页面上的周期性补帧阈值：低于它说明画面基本没动，不重复取 */
    private static final double MOTION_SAME = 4.8;
    /** 距上一张已采纳帧的最长间隔，保证静止页也能持续覆盖 */
    private static final long PERIODIC_MS = 800;
    /** 单次扫描的取样上限，长视频自动降频，避免解码开销失控 */
    private static final int MAX_SCANS = 1200;

    /**
     * 运动自适应抽帧。
     *
     * 原来按固定 2fps 采样，遇到手机快速平移会整段跳过内容（实测一份 28 秒的试卷录像
     * 漏掉了第 35 题、完形 54/55 的选项和语法填空后半段）。改成逐帧扫描 + 按画面位移
     * 触发取帧：内容移动越大取帧越密，静止页只保留少量代表帧。
     *
     * @param fps 参考帧率下限，仅用于计算扫描步长（不再决定取帧数量）
     */
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

            // 扫描步长：短视频细扫（150ms），长视频粗扫，总扫描次数不超过 MAX_SCANS
            long scanMs = Math.max(150L, durationMs / MAX_SCANS);
            if (fps > 0) scanMs = Math.max(scanMs, Math.min(500L, 1000L / Math.max(1, fps)));
            int total = (int) Math.max(1, durationMs / scanMs);

            byte[] prevSmall = null;
            long lastAcceptedMs = Long.MIN_VALUE / 2;

            for (int i = 0; i < total; i++) {
                long timeMs = i * scanMs;
                Bitmap bmp = r.getFrameAtTime(timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (bmp != null) {
                    double sharp = laplacianVariance(bmp);
                    byte[] small = null;
                    double motion = Double.MAX_VALUE;
                    if (sharp >= MIN_SHARPNESS) {
                        small = smallGray(bmp);
                        if (prevSmall != null) motion = meanAbsDiff(prevSmall, small);
                    }

                    boolean accept;
                    if (sharp < MIN_SHARPNESS) {
                        accept = false;                       // 糊帧不要
                    } else if (prevSmall == null) {
                        accept = true;                        // 第一张清晰帧
                    } else if (motion >= MOTION_ACCEPT) {
                        accept = true;                        // 画面大幅移动：可能是快速滑动
                    } else if (timeMs - lastAcceptedMs >= PERIODIC_MS && motion >= MOTION_SAME) {
                        accept = true;                        // 静止页面的周期补帧
                    } else {
                        accept = false;
                    }

                    if (accept) {
                        prevSmall = small;
                        lastAcceptedMs = timeMs;
                        kept++;
                        if (!sink.onFrame(bmp, timeMs, sharp, kept, total)) {
                            bmp.recycle();
                            break;
                        }
                        if (maxFrames > 0 && kept >= maxFrames) break;
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

    /** 64×36 灰度缩略图，用于判断画面位移。 */
    private static byte[] smallGray(Bitmap src) {
        int w = 64, h = 36;
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        byte[] g = new byte[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (byte) ((0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)));
        }
        return g;
    }

    /** 平均绝对灰度差：越大说明画面变化越大。 */
    private static double meanAbsDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return (double) sum / n;
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