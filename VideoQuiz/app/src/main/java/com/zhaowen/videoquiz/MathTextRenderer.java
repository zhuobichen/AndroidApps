package com.zhaowen.videoquiz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

import ru.noties.jlatexmath.JLatexMathDrawable;

/** App 内部公式渲染：把含 $...$ / $$...$$ 的文本排成位图（公式用 jlatexmath 绘制）。 */
public class MathTextRenderer {

    private static boolean inited = false;

    public static synchronized void init(Context ctx) {
        if (inited) return;
        try {
            ru.noties.jlatexmath.JLatexMathAndroid.init(ctx);
        } catch (Throwable ignored) {}
        inited = true;
    }

    /** 去掉 Markdown 标记（模型常把整页转成 md） */
    private static String cleanMarkdown(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                  .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                  .replaceAll("`([^`]+)`", "$1");
        return t;
    }

    private static class Item {
        String text;
        Drawable formula;
        Bitmap bitmap;
        float x, w;
        float h;
    }

    private static class Line {
        List<Item> items = new ArrayList<>();
        float used;
        float height;
    }

    public static Bitmap render(Context ctx, String text, int widthPx, float textSizePx, int color) {
        init(ctx);
        text = cleanMarkdown(text);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(textSizePx);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float baseLineGap = textH * 1.35f;

        List<Line> lines = layout(text, paint, widthPx, textSizePx, color, baseLineGap);

        int totalH = 8;
        for (Line ln : lines) totalH += (int) ln.height + 6;

        Bitmap bmp = Bitmap.createBitmap(Math.max(widthPx, 1), Math.max(totalH, 16),
                Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        float y = 4;
        for (Line ln : lines) {
            float lineTop = y;
            float lineH = Math.max(ln.height, baseLineGap);
            float baseline = lineTop + (lineH - textH) / 2f - fm.ascent;
            for (Item it : ln.items) {
                if (it.bitmap != null) {
                    int left = (int) it.x;
                    int top = (int) (lineTop + (lineH - it.h) / 2f);
                    cv.drawBitmap(it.bitmap, left, top, null);
                } else if (it.text != null) {
                    cv.drawText(it.text, it.x, baseline, paint);
                }
            }
            y = lineTop + lineH + 6;
        }
        return bmp;
    }

    private static List<Line> layout(String text, TextPaint paint, int maxW,
                                     float textSizePx, int color, float baseLineGap) {
        List<Line> lines = new ArrayList<>();
        Line cur = new Line();
        lines.add(cur);

        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$$", i) || text.charAt(i) == '$') {
                boolean dbl = text.startsWith("$$", i);
                int start = i + (dbl ? 2 : 1);
                String close = dbl ? "$$" : "$";
                int end = text.indexOf(close, start);
                if (end < 0) { addWord(cur, lines, text.substring(i), paint, maxW); break; }
                String tex = text.substring(start, end).trim();
                i = end + close.length();
                Drawable dr = null;
                try {
                    dr = JLatexMathDrawable.builder(tex).textSize(textSizePx * 1.1f)
                            .color(color).build();
                } catch (Throwable ignored) {}
                if (dr == null) { addWord(cur, lines, "$" + tex + "$", paint, maxW); continue; }
                float w = Math.max(1, dr.getIntrinsicWidth());
                float h = Math.max(1, dr.getIntrinsicHeight());
                if (cur.used + w > maxW && cur.used > 0) { cur = newLine(lines); }
                Item it = new Item();
                it.formula = dr;
                it.w = w;
                it.h = h;
                it.x = cur.used;
                // 先把公式单独画进小位图，避免 drawable 的定位问题
                try {
                    Bitmap fb = Bitmap.createBitmap(Math.max(1, (int) w), Math.max(1, (int) h),
                            Bitmap.Config.ARGB_8888);
                    Canvas fc = new Canvas(fb);
                    dr.setBounds(0, 0, (int) w, (int) h);
                    dr.draw(fc);
                    it.bitmap = fb;
                } catch (Throwable ignored) {}
                cur.items.add(it);
                cur.used += w + paint.measureText(" ");
                cur.height = Math.max(cur.height, h);
            } else {
                int next = i;
                while (next < text.length() && text.charAt(next) != '$') next++;
                String chunk = text.substring(i, next);
                i = next;
                String[] parts = chunk.split("\n", -1);
                for (int k = 0; k < parts.length; k++) {
                    if (k > 0) cur = newLine(lines);
                    String[] words = parts[k].split("(?<=\\s)");
                    for (String wd : words) {
                        if (wd.isEmpty()) continue;
                        if (cur.used > 0 && cur.used + paint.measureText(wd) > maxW) cur = newLine(lines);
                        Item it = new Item();
                        it.text = wd;
                        it.w = paint.measureText(wd);
                        it.x = cur.used;
                        cur.items.add(it);
                        cur.used += it.w;
                        cur.height = Math.max(cur.height, baseLineGap);
                    }
                }
            }
        }
        if (lines.isEmpty()) lines.add(new Line());
        return lines;
    }

    private static void addWord(Line cur, List<Line> lines, String s, TextPaint paint, int maxW) {
        if (cur.used > 0 && cur.used + paint.measureText(s) > maxW) newLine(lines);
        Item it = new Item();
        it.text = s;
        it.w = paint.measureText(s);
        it.x = cur.used;
        cur.items.add(it);
        cur.used += it.w;
    }

    private static Line newLine(List<Line> lines) {
        Line l = new Line();
        lines.add(l);
        return l;
    }
}