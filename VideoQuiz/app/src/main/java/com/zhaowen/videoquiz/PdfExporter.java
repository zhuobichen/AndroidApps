package com.zhaowen.videoquiz;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** 生成 A4 PDF：前半是题目，后半是答案与解析。 */
public class PdfExporter {

    private static final int W = 595, H = 842, M = 44;

    public static File export(File dir, List<Question> qs, boolean withAnswers) throws Exception {
        PdfDocument doc = new PdfDocument();
        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.BLACK);
        title.setTextSize(18f);
        title.setFakeBoldText(true);

        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.BLACK);
        body.setTextSize(10.5f);

        TextPaint grey = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        grey.setColor(Color.rgb(90, 90, 90));
        grey.setTextSize(9f);

        int pageNo = 1;
        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        Canvas cv = page.getCanvas();
        float y = M;

        cv.drawText("题目与答案", M, y, title);
        y += 16;
        cv.drawText("由 VideoQuiz 生成", M, y, grey);
        y += 26;

        for (Question q : qs) {
            String block = q.no + ". " + q.stem;
            for (String o : q.options) block += "\n    " + o;

            StaticLayout sl = StaticLayout.Builder
                    .obtain(block, 0, block.length(), body, W - 2 * M)
                    .setLineSpacing(2f, 1f)
                    .build();
            if (y + sl.getHeight() > H - M) {
                doc.finishPage(page);
                pageNo++;
                page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                cv = page.getCanvas();
                y = M;
            }
            cv.save();
            cv.translate(M, y);
            sl.draw(cv);
            cv.restore();
            y += sl.getHeight() + 12;
        }

        if (withAnswers) {
            if (y + 60 > H - M) {
                doc.finishPage(page);
                pageNo++;
                page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                cv = page.getCanvas();
                y = M;
            }
            y += 10;
            cv.drawText("参考答案", M, y, title);
            y += 24;
            for (Question q : qs) {
                String block = q.no + ". " + q.answer + (q.reason.isEmpty() ? "" : "　" + q.reason);
                StaticLayout sl = StaticLayout.Builder
                        .obtain(block, 0, block.length(), body, W - 2 * M)
                        .setLineSpacing(2f, 1f)
                        .build();
                if (y + sl.getHeight() > H - M) {
                    doc.finishPage(page);
                    pageNo++;
                    page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                    cv = page.getCanvas();
                    y = M;
                }
                cv.save();
                cv.translate(M, y);
                sl.draw(cv);
                cv.restore();
                y += sl.getHeight() + 6;
            }
        }

        doc.finishPage(page);
        File out = new File(dir, "VideoQuiz-" + System.currentTimeMillis() + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
        return out;
    }
}