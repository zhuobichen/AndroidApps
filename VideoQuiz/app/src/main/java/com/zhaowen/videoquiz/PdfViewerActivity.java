package com.zhaowen.videoquiz;

import android.content.Intent;
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
    private File currentFile;
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

        currentFile = file;

        TextView title = new TextView(this);
        title.setText(file.getName());
        title.setTextSize(13);
        title.setTextColor(Color.parseColor("#5B6472"));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        com.google.android.material.button.MaterialButton btnSave =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSave.setText("保存到手机");
        btnSave.setTextSize(12);
        btnSave.setAllCaps(false);
        btnSave.setMinWidth(0);
        btnSave.setMinimumWidth(0);
        btnSave.setInsetTop(0);
        btnSave.setInsetBottom(0);
        btnSave.setOnClickListener(v -> saveToDownloads());
        bar.addView(btnSave, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        com.google.android.material.button.MaterialButton btnShare =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnShare.setText("分享");
        btnShare.setTextSize(12);
        btnShare.setAllCaps(false);
        btnShare.setMinWidth(0);
        btnShare.setMinimumWidth(0);
        btnShare.setInsetTop(0);
        btnShare.setInsetBottom(0);
        btnShare.setOnClickListener(v -> sharePdf());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        shareLp.leftMargin = dp(6);
        bar.addView(btnShare, shareLp);

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

    /** 分享：走系统分享面板，微信 / QQ / 邮件 / 保存到文件都在里面。 */
    private void sharePdf() {
        if (currentFile == null || !currentFile.isFile()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", currentFile);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, currentFile.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享 PDF"));
        } catch (Throwable e) {
            Toast.makeText(this, "分享失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 保存到系统「下载」目录（Android 10+ 走 MediaStore，不需要存储权限）。 */
    private void saveToDownloads() {
        if (currentFile == null || !currentFile.isFile()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "系统版本较低，请用「分享 → 保存到文件」", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "正在保存…", Toast.LENGTH_SHORT).show();
        final File source = currentFile;
        new Thread(() -> {
            String message;
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, source.getName());
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/卷灵");

                android.net.Uri target = getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IllegalStateException("无法在下载目录创建文件");

                try (java.io.InputStream in = new java.io.FileInputStream(source);
                     java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IllegalStateException("无法写入目标文件");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }

                message = "已保存到 下载/卷灵/" + source.getName();
            } catch (Throwable e) {
                message = "保存失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String text = message;
            ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "pdf-save").start();
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
