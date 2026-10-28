package com.zhaowen.videoquiz;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/** 内置 PDF 阅读器：用系统 PdfRenderer 逐页渲染，竖向滚动阅读。 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        File file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, "找不到这个 PDF", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F8"));

        TextView title = new TextView(this);
        title.setText(file.getName());
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#14181F"));
        title.setPadding(dp(16), dp(14), dp(16), dp(10));

        ScrollView scroll = new ScrollView(this);
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.setPadding(dp(8), 0, dp(8), dp(16));
        scroll.addView(pages);

        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            renderPages(pages, title, file.getName());
        } catch (Throwable e) {
            Toast.makeText(this, "打不开这个 PDF：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void renderPages(LinearLayout pages, TextView title, String name) {
        final int count = renderer.getPageCount();
        title.setText(name + "  ·  共 " + count + " 页");

        // 逐页渲染，避免一次性占用太多内存
        new Thread(() -> {
            for (int i = 0; i < count; i++) {
                Bitmap bitmap = null;
                try {
                    synchronized (renderer) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        int targetWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
                        int targetHeight = (int) (targetWidth * (float) page.getHeight() / page.getWidth());
                        bitmap = Bitmap.createBitmap(targetWidth, Math.max(1, targetHeight), Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                    }
                } catch (Throwable ignored) {
                    bitmap = null;
                }

                final Bitmap frame = bitmap;
                final int index = i;
                ui.post(() -> {
                    if (frame == null) {
                        TextView failed = new TextView(this);
                        failed.setText("第 " + (index + 1) + " 页渲染失败");
                        failed.setPadding(dp(8), dp(12), dp(8), dp(12));
                        pages.addView(failed);
                        return;
                    }
                    ImageView view = new ImageView(this);
                    view.setImageBitmap(frame);
                    view.setAdjustViewBounds(true);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(10);
                    pages.addView(view, lp);
                });
            }
        }, "pdf-render").start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            if (renderer != null) renderer.close();
        } catch (Throwable ignored) {
        }
        try {
            if (descriptor != null) descriptor.close();
        } catch (Throwable ignored) {
        }
        renderer = null;
        descriptor = null;
        super.onDestroy();
    }
}
