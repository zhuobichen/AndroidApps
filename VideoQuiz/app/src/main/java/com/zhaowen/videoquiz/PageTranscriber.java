package com.zhaowen.videoquiz;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * 整页识别：裁边 → 切 3 条 → 每条用两种裁剪各识别一次 → 逐行投票。
 * 目的是压住模型在模糊符号上的随机错误（同一处时对时错）。
 */
public class PageTranscriber {

    private static final int TILES = 3;
    private static final int PASSES = 2;

    public static String transcribe(VisionClient client, Bitmap frame) throws Exception {
        Bitmap doc = DocumentCropper.crop(frame);

        List<List<List<String>>> samples = new ArrayList<>();
        for (int t = 0; t < TILES; t++) samples.add(new ArrayList<>());

        for (int pass = 0; pass < PASSES; pass++) {
            List<Bitmap> tiles = FrameTiler.split(doc, TILES, 0.18f, 1500 + pass * 60);
            for (int t = 0; t < tiles.size() && t < TILES; t++) {
                Bitmap tile = tiles.get(t);
                if (pass == 1) tile = shift(tile, -5);
                try {
                    String part = client.transcribePage(tile);
                    samples.get(t).add(split(part));
                } catch (Throwable e) {
                    android.util.Log.w("VideoQuiz", "tile " + t + " pass " + pass + " failed: " + e.getMessage());
                } finally {
                    if (tile != null) tile.recycle();
                }
            }
        }

        StringBuilder out = new StringBuilder();
        for (int t = 0; t < TILES; t++) {
            List<String> voted = vote(samples.get(t));
            for (String ln : voted) out.append(ln).append('\n');
        }
        return out.toString().trim();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String ln : s.split("\n")) {
            if (!ln.trim().isEmpty()) out.add(ln.trim());
        }
        return out;
    }

    /** 以最长的一次为基准，逐行在其它次里找相似行投票 */
    private static List<String> vote(List<List<String>> passes) {
        if (passes.isEmpty()) return new ArrayList<>();
        if (passes.size() == 1) return passes.get(0);

        List<String> ref = passes.get(0);
        for (List<String> p : passes) if (p.size() > ref.size()) ref = p;

        List<String> out = new ArrayList<>();
        for (int i = 0; i < ref.size(); i++) {
            String base = ref.get(i);
            String baseKey = norm(base);
            List<String> cands = new ArrayList<>();
            cands.add(base);
            for (List<String> p : passes) {
                if (p == ref) continue;
                String best = null;
                double bestSim = 0;
                for (String q : p) {
                    double sim = sim(baseKey, norm(q));
                    if (sim > bestSim) { bestSim = sim; best = q; }
                }
                if (best != null && bestSim >= 0.55) cands.add(best);
            }
            // 出现次数最多者胜；同票取更长的
            String win = base;
            int winCount = 0;
            for (String c : cands) {
                int cnt = 0;
                for (String d : cands) if (sim(norm(c), norm(d)) >= 0.9) cnt++;
                if (cnt > winCount || (cnt == winCount && c.length() > win.length())) {
                    winCount = cnt;
                    win = c;
                }
            }
            out.add(win);
        }
        return out;
    }

    private static String norm(String s) {
        return s.replaceAll("[\\s$\\\\{}()\\[\\]]", "").toLowerCase();
    }

    private static double sim(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        int n = 20000;
        // 三元组 Jaccard
        java.util.Set<String> sa = new java.util.HashSet<>(), sb = new java.util.HashSet<>();
        for (int i = 0; i + 2 < a.length(); i++) sa.add(a.substring(i, i + 3));
        for (int i = 0; i + 2 < b.length(); i++) sb.add(b.substring(i, i + 3));
        if (sa.isEmpty() || sb.isEmpty()) return a.equals(b) ? 1 : 0;
        int inter = 0;
        for (String s : sa) if (sb.contains(s)) inter++;
        return (double) inter / (sa.size() + sb.size() - inter);
    }

    /** 上下平移几个像素，制造不同的输入 */
    private static Bitmap shift(Bitmap src, int dy) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawBitmap(src, 0, dy, new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }
}