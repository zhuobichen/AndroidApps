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

/** 生成 A4 PDF：按视频顺序排出「原文 → 题目」，最后附答案。 */
public class PdfExporter {

    private static final int W = 595, H = 842, M = 44;

    public static class Block {
        public int frame;
        public String passage;
        public List<Question> questions = new ArrayList<>();
    }

    /** 负责分页、落笔 */
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

    public static File export(File dir, List<Block> blocks, boolean withAnswers) throws Exception {
        PdfDocument doc = new PdfDocument();
        Pager pg = new Pager(doc);

        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.BLACK);
        title.setTextSize(18f);
        title.setFakeBoldText(true);

        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.BLACK);
        body.setTextSize(10.5f);

        TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.rgb(26, 115, 232));
        label.setTextSize(11.5f);
        label.setFakeBoldText(true);

        TextPaint grey = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        grey.setColor(Color.rgb(90, 90, 90));
        grey.setTextSize(9f);

        pg.cv.drawText("试卷原文与答案", M, pg.y, title);
        pg.y += 16;
        pg.cv.drawText("由 VideoQuiz 从录像自动生成", M, pg.y, grey);
        pg.y += 26;

        List<Question> all = new ArrayList<>();
        for (Block b : blocks) all.addAll(b.questions);

        for (Block b : blocks) {
            if (b.passage != null && !b.passage.trim().isEmpty()) {
                pg.para("【原文】", label, 6);
                pg.para(b.passage.trim(), body, 14);
            }
            for (Question q : b.questions) {
                StringBuilder sb = new StringBuilder();
                sb.append(q.no).append(". ").append(q.stem);
                for (String o : q.options) sb.append("\n    ").append(o);
                pg.para(sb.toString(), body, 12);
            }
        }

        if (withAnswers && !all.isEmpty()) {
            pg.gap(10);
            pg.para("参考答案", title, 14);
            for (Question q : all) {
                String s = q.no + ". " + q.answer + (q.reason.isEmpty() ? "" : "　" + q.reason);
                pg.para(s, body, 6);
            }
        }

        pg.finish();
        File out = new File(dir, "VideoQuiz-" + System.currentTimeMillis() + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }
}