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

    /** 保存时弹出的系统文件选择器回调。 */
    private final androidx.activity.result.ActivityResultLauncher<Intent> saveLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null
                                && result.getData().getData() != null) {
                            copyTo(result.getData().getData());
                        }
                    });
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
        pageView.setAdjustViewBounds(false);
        pageView.setScaleType(ImageView.ScaleType.FIT_START);
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
            boolean renderBlank = false;
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

                    // 用 ARGB_8888：RGB_565 在部分 PDF 上会渲染成空白页。
                    // 单页 1080×1500 约 6.5MB，翻页即回收，不会 OOM。
                    bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();

                    if (isAllWhite(bitmap)) renderBlank = true;
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            final Bitmap rendered = bitmap;
            final String failure = error;
            final boolean blank = renderBlank;
            ui.post(() -> {
                if (rendered != null) {
                    Bitmap old = currentBitmap;
                    currentBitmap = rendered;
                    pageIndex = wanted;

                    // 明确按位图比例设置高度：只靠 adjustViewBounds 在 ScrollView 里容易算成 0
                    int viewWidth = Math.max(1, pageView.getWidth());
                    if (viewWidth <= dp(16)) viewWidth = getResources().getDisplayMetrics().widthPixels - dp(16);
                    int viewHeight = Math.max(1, (int) ((long) viewWidth * rendered.getHeight() / Math.max(1, rendered.getWidth())));
                    pageView.getLayoutParams().height = viewHeight;
                    pageView.setImageBitmap(rendered);
                    pageView.requestLayout();

                    pageLabel.setText((wanted + 1) + " / " + count + (blank ? "  · 空白页" : ""));
                    if (blank) {
                        Toast.makeText(this, "第 " + (wanted + 1) + " 页渲染为空白（PDF 用了不支持的编码）",
                                Toast.LENGTH_LONG).show();
                    }
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
    /** 系统文件选择器：让用户自己选保存位置（下载 / 文档 / 云盘都行）。 */
    private void saveToDownloads() {
        if (currentFile == null || !currentFile.isFile()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, currentFile.getName());
            saveLauncher.launch(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "打开保存面板失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 把当前 PDF 复制到用户选定的位置。 */
    private void copyTo(android.net.Uri target) {
        final File source = currentFile;
        if (source == null || !source.isFile()) return;

        Toast.makeText(this, "正在保存…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                try (java.io.InputStream in = new java.io.FileInputStream(source);
                     java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IllegalStateException("无法写入目标位置");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                message = "已保存：" + source.getName();
            } catch (Throwable e) {
                message = "保存失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String text = message;
            ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "pdf-save").start();
    }

    /** 采样判断整页是否纯白，用于识别「渲染成功但没内容」的情况。 */
    private static boolean isAllWhite(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return true;

            int stepY = Math.max(1, height / 40);
            int stepX = Math.max(1, width / 40);
            int[] row = new int[width];

            for (int y = 0; y < height; y += stepY) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x += stepX) {
                    if ((row[x] & 0x00FFFFFF) != 0x00FFFFFF) return false;
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
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
