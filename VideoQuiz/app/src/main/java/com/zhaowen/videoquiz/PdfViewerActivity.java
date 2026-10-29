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

/**
 * 内置 PDF 阅读器。
 *
 * 一次只保留当前页的 Bitmap：整卷 40 页如果全部渲染常驻，会吃掉几百 MB 直接 OOM，
 * 这也是之前「点开 PDF 就闪退回主界面」的原因。
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private File currentFile;

    private ImageView pageView;
    private TextView pageLabel;
    private Bitmap currentBitmap;
    private int pageIndex = 0;

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
        currentFile = file;

        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
        } catch (Throwable e) {
            Toast.makeText(this, "打不开这个 PDF：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        buildUi();
        showPage(0);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F8"));

        // ── 顶栏：文件名 + 保存 + 分享 ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));

        TextView title = new TextView(this);
        title.setText(currentFile.getName());
        title.setTextSize(13);
        title.setTextColor(Color.parseColor("#5B6472"));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        com.google.android.material.button.MaterialButton save = outlined("保存到手机");
        save.setOnClickListener(v -> saveToDownloads());
        bar.addView(save, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        com.google.android.material.button.MaterialButton share = outlined("分享");
        share.setOnClickListener(v -> sharePdf());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        shareLp.leftMargin = dp(6);
        bar.addView(share, shareLp);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 页面：单页，可上下滚动查看长页 ──
        ScrollView scroll = new ScrollView(this);
        pageView = new ImageView(this);
        pageView.setAdjustViewBounds(true);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(8), dp(8), dp(8), dp(8));
        holder.addView(pageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(holder);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 底栏：翻页 ──
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));

        com.google.android.material.button.MaterialButton prev = outlined("上一页");
        prev.setOnClickListener(v -> showPage(pageIndex - 1));
        nav.addView(prev, new LinearLayout.LayoutParams(0, dp(44), 1f));

        pageLabel = new TextView(this);
        pageLabel.setTextSize(13);
        pageLabel.setTextColor(Color.parseColor("#5B6472"));
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(44), 1.2f));

        com.google.android.material.button.MaterialButton next = outlined("下一页");
        next.setOnClickListener(v -> showPage(pageIndex + 1));
        nav.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1f));

        root.addView(nav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private com.google.android.material.button.MaterialButton outlined(String text) {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    /** 渲染指定页；先回收上一页的位图，保证同时只有一张常驻。 */
    private void showPage(int index) {
        final int count = renderer.getPageCount();
        if (count <= 0) return;

        int target = Math.max(0, Math.min(index, count - 1));
        final int wanted = target;

        new Thread(() -> {
            Bitmap bitmap = null;
            String error = null;
            try {
                synchronized (renderer) {
                    PdfRenderer.Page page = renderer.openPage(wanted);
                    int width = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(24));
                    int height = (int) ((long) width * page.getHeight() / Math.max(1, page.getWidth()));

                    // 长页面限高，避免单页位图过大
                    int maxHeight = dp(4000);
                    if (height > maxHeight) {
                        width = (int) ((long) width * maxHeight / height);
                        height = maxHeight;
                    }

                    // RGB_565：白底文档足够，内存只有 ARGB 的一半
                    bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.RGB_565);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            final Bitmap rendered = bitmap;
            final String failure = error;
            ui.post(() -> {
                if (rendered != null) {
                    Bitmap old = currentBitmap;
                    currentBitmap = rendered;
                    pageIndex = wanted;
                    pageView.setImageBitmap(rendered);
                    pageLabel.setText((wanted + 1) + " / " + count);
                    if (old != null && old != rendered && !old.isRecycled()) old.recycle();
                } else {
                    pageLabel.setText("第 " + (wanted + 1) + " 页渲染失败");
                    if (failure != null) {
                        Toast.makeText(this, "渲染失败：" + failure, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "pdf-page").start();
    }

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
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
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
