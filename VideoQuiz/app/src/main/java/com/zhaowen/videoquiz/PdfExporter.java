package com.zhaowen.videoquiz;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 生成两份 PDF：试卷（原文+题目）、参考答案（答案+作文范文）。 */
public class PdfExporter {

    private static final int W = 595, H = 842, M = 44;

    public static class Block {
        public int frame;
        public String passage;
        public List<Question> questions = new ArrayList<>();
    }

    private static class Pager {
        final PdfDocument doc;
        PdfDocument.Page page;
        Canvas cv;
        int pageNo = 0;
        float y = M;

        Pager(PdfDocument d) { doc = d; newPage(); }

        void newPage() {
            if (page != null) doc.finishPage(page);
            pageNo++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
            cv = page.getCanvas();
            y = M;
        }

        void gap(float h) {
            y += h;
            if (y > H - M) newPage();
        }

        void para(String text, TextPaint p, float after) {
            StaticLayout sl = StaticLayout.Builder
                    .obtain(text, 0, text.length(), p, W - 2 * M)
                    .setLineSpacing(2f, 1f)
                    .build();
            if (y + sl.getHeight() > H - M) newPage();
            cv.save();
            cv.translate(M, y);
            sl.draw(cv);
            cv.restore();
            y += sl.getHeight() + after;
        }

        void finish() { if (page != null) doc.finishPage(page); }
    }

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        return p;
    }

    /** 试卷：置信度 + 原文 + 题目 + 写作题（不含答案） */
    public static File exportPaper(File dir, List<Block> blocks, List<String> warnings,
                                   List<String> writings, List<String> confidence) throws Exception {
        PdfDocument doc = new PdfDocument();
        Pager pg = new Pager(doc);

        TextPaint title = paint(18f, Color.BLACK, true);
        TextPaint body = paint(10.5f, Color.BLACK, false);
        TextPaint label = paint(11.5f, Color.rgb(26, 115, 232), true);
        TextPaint grey = paint(9f, Color.rgb(90, 90, 90), false);

        pg.cv.drawText("试卷", M, pg.y, title);
        pg.y += 16;
        pg.cv.drawText("由 VideoQuiz 从录像自动生成", M, pg.y, grey);
        pg.y += 18;

        if (confidence != null && !confidence.isEmpty()) {
            pg.para(confidence.get(0), paint(10.5f, Color.rgb(26, 115, 232), true), 5);
            for (int i = 1; i < confidence.size(); i++) pg.para("· " + confidence.get(i), grey, 2);
        }
        pg.gap(8);

        for (Block b : blocks) {
            if (b.passage != null && !b.passage.trim().isEmpty()) {
                pg.para("【原文】", label, 5);
                pg.para(b.passage.trim(), body, 12);
            }
            for (Question q : b.questions) {
                StringBuilder sb = new StringBuilder();
                sb.append(q.no).append(". ").append(q.stem);
                for (String o : q.options) sb.append("\n    ").append(o);
                pg.para(sb.toString(), body, 11);
            }
        }

        if (writings != null && !writings.isEmpty()) {
            pg.gap(10);
            pg.para("写作题", title, 10);
            for (int i = 0; i < writings.size(); i++) {
                pg.para("作文 " + (i + 1) + "：" + writings.get(i), body, 10);
            }
        }

        if (warnings != null && !warnings.isEmpty()) {
            pg.gap(10);
            pg.para("识别质量提示", paint(10.5f, Color.rgb(198, 40, 40), true), 6);
            for (String w : warnings) pg.para("· " + w, body, 3);
            pg.para("如果是拍摄问题（抖动、反光、没拍全），建议重拍对应段落后再导入。", grey, 8);
        }

        pg.finish();
        return save(doc, dir, "试卷-");
    }

    /** 整页模式：按页输出转录结果（含 LaTeX 公式） */
    public static File exportPages(android.content.Context ctx, File dir, List<String> pages,
                                   List<String> confidence) throws Exception {
        PdfDocument doc = new PdfDocument();
        Pager pg = new Pager(doc);

        TextPaint title = paint(18f, Color.BLACK, true);
        TextPaint body = paint(9.5f, Color.BLACK, false);
        TextPaint label = paint(10.5f, Color.rgb(26, 115, 232), true);
        TextPaint grey = paint(9f, Color.rgb(90, 90, 90), false);

        pg.cv.drawText("试卷（整页转录）", M, pg.y, title);
        pg.y += 16;
        pg.cv.drawText("由 VideoQuiz 生成，数学公式已排版", M, pg.y, grey);
        pg.y += 18;
        if (confidence != null && !confidence.isEmpty()) {
            pg.para(confidence.get(0), paint(10f, Color.rgb(26, 115, 232), true), 6);
        }

        android.graphics.Paint img = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < pages.size(); i++) {
            pg.gap(8);
            pg.para("—— 第 " + (i + 1) + " 页 ——", label, 6);
            android.graphics.Bitmap bmp = null;
            try {
                bmp = MathTextRenderer.render(ctx, pages.get(i), (W - 2 * M) * 2, 19f,
                        android.graphics.Color.BLACK);
            } catch (Throwable ignored) {}
            if (bmp == null) {
                pg.para(pages.get(i), body, 10);
                continue;
            }
            float h = bmp.getHeight() / 2f;
            if (pg.y + h > H - M) pg.newPage();
            android.graphics.Rect dst = new android.graphics.Rect(
                    M, (int) pg.y, M + (W - 2 * M), (int) (pg.y + h));
            pg.cv.drawBitmap(bmp, null, dst, img);
            pg.y += h + 10;
            bmp.recycle();
        }
        pg.finish();
        return save(doc, dir, "试卷-");
    }

    /** 参考答案：逐题答案 + 写作思路与范文 */
    public static File exportAnswers(File dir, List<Question> questions,
                                     List<String> writings, List<String> essays) throws Exception {
        PdfDocument doc = new PdfDocument();
        Pager pg = new Pager(doc);

        TextPaint title = paint(18f, Color.BLACK, true);
        TextPaint body = paint(10.5f, Color.BLACK, false);
        TextPaint label = paint(11.5f, Color.rgb(26, 115, 232), true);
        TextPaint grey = paint(9f, Color.rgb(90, 90, 90), false);

        pg.cv.drawText("参考答案", M, pg.y, title);
        pg.y += 16;
        pg.cv.drawText("由 VideoQuiz 生成，仅供参考", M, pg.y, grey);
        pg.y += 26;

        for (Question q : questions) {
            String s = q.no + ". " + q.answer
                    + (q.reason == null || q.reason.isEmpty() ? "" : "　" + q.reason);
            pg.para(s, body, 5);
        }

        if (writings != null && !writings.isEmpty()) {
            pg.gap(12);
            pg.para("写作题", title, 10);
            for (int i = 0; i < writings.size(); i++) {
                pg.para("作文 " + (i + 1) + "：" + writings.get(i), body, 8);
                if (essays != null && i < essays.size() && !essays.get(i).isEmpty()) {
                    String raw = essays.get(i).trim();
                    int cut = raw.indexOf('\n');
                    if (cut < 0) cut = raw.indexOf('。');
                    String idea = cut > 0 ? raw.substring(0, cut + 1).trim() : "";
                    String essay = cut > 0 ? raw.substring(Math.min(raw.length(), cut + 1)).trim() : raw;
                    if (!idea.isEmpty()) pg.para("【写作思路】" + idea, grey, 5);
                    pg.para("【参考范文·不是唯一答案】", label, 4);
                    pg.para(essay, body, 14);
                }
            }
        }

        pg.finish();
        return save(doc, dir, "答案-");
    }

    private static File save(PdfDocument doc, File dir, String prefix) throws Exception {
        File out = new File(dir, prefix + System.currentTimeMillis() + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }
}