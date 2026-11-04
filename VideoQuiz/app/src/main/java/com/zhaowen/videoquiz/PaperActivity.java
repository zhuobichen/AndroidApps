package com.zhaowen.videoquiz;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分段识别页。
 *
 * 一套卷可以分多次提交：每次「加一段视频」或「加一张照片」，电脑端会把它当成同一套试卷的
 * 新一段处理，已出的答案会累积在试卷上，后面的段不会重复识别前面已定的题。
 *
 * 与整卷模式的区别只在这里：走 /api/units（带 taskMode=segment 和同一个 paperId），
 * 而不是 /api/jobs。
 */
public class PaperActivity extends AppCompatActivity {

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView tvPaper;
    private TextView tvSummary;
    private LinearLayout answerList;
    private ScrollView scroll;

    private volatile boolean polling = true;
    private volatile boolean uploading = false;
    private String lastRenderKey = "";
    /** 最近一次拉到的试卷数据，转发时直接用它，保证发出去的是屏幕上的内容 */
    private JSONObject lastPaper = null;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                uploadPicked(uri);
            });

    /** 系统相机拍的原图，拍完直接当成这一套卷的一张照片上传 */
    private File pendingShotFile;
    private Uri pendingShotUri;

    private final ActivityResultLauncher<Intent> shotLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                File shot = pendingShotFile;
                pendingShotFile = null;
                pendingShotUri = null;
                if (result.getResultCode() != RESULT_OK || shot == null || !shot.isFile()) return;
                if (shot.length() < 20 * 1024) {
                    toast("这张照片异常小（" + (shot.length() / 1024) + "KB），可能相机没写原图，重拍一次");
                    return;
                }
                String name = shot.getName();
                uploading = true;
                tvSummary.setText("正在上传 " + name + "…");
                pool.execute(() -> {
                    try {
                        doUpload(shot, name, "image");
                    } finally {
                        shot.delete();
                    }
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paper);

        prefs = new Prefs(this);
        tvPaper = findViewById(R.id.tvPaper);
        tvSummary = findViewById(R.id.tvSummary);
        answerList = findViewById(R.id.answerList);
        scroll = findViewById(R.id.scroll);

        findViewById(R.id.toolbar).setOnClickListener(v -> finish());
        findViewById(R.id.btnTakePhoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnAddVideo).setOnClickListener(v -> picker.launch(new String[]{"video/*"}));
        findViewById(R.id.btnAddPhoto).setOnClickListener(v -> picker.launch(new String[]{"image/*"}));
        findViewById(R.id.btnNewPaper).setOnClickListener(v -> startNewPaper());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareAnswers());
        findViewById(R.id.btnHistory).setOnClickListener(v -> showPaperHistory());

        ensurePaper();
        renderEmpty();
        startPolling();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        pool.shutdownNow();
        super.onDestroy();
    }

    // ── 试卷标识 ──────────────────────────────────────────────────

    private void ensurePaper() {
        if (prefs.paperId() == null || prefs.paperId().isEmpty()) {
            String id = "paper-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.setPaperId(id);
            prefs.setPaperTitle("未命名试卷");
        }
        if (prefs.paperTitle() == null || prefs.paperTitle().isEmpty()) {
            prefs.setPaperTitle("未命名试卷");
        }
    }

    private void startNewPaper() {
        String id = "paper-" + UUID.randomUUID().toString().substring(0, 8);
        prefs.setPaperId(id);
        prefs.setPaperTitle("未命名试卷");
        lastRenderKey = "";
        renderEmpty();
        toast("已换新卷，接着加的段会算到这套新卷上");
        refreshNow();
    }

    // ── 上传一段/一张 ─────────────────────────────────────────────

    private void uploadPicked(Uri uri) {
        if (!canUpload()) return;

        String name = displayName(uri);
        String kind = name.toLowerCase().matches(".*\\.(mp4|mov|m4v|mkv|webm|avi|3gp|mpeg|mpg)$")
                ? "video-segment" : "image";

        uploading = true;
        tvSummary.setText("正在上传 " + name + "…");

        pool.execute(() -> {
            File staged = null;
            try {
                staged = copyToCache(uri, name);
                doUpload(staged, name, kind);
            } catch (Throwable e) {
                failUpload(e);
            } finally {
                if (staged != null) staged.delete();
            }
        });
    }

    /** 打开系统相机，全尺寸原图写到 App 私有目录，不经过缩略图中转 */
    private void takePhoto() {
        if (!canUpload()) return;
        try {
            File dir = new File(getExternalFilesDir(null), "shots");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建拍照目录");

            pendingShotFile = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
            pendingShotUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", pendingShotFile);

            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingShotUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (intent.resolveActivity(getPackageManager()) == null) {
                toast("这台手机没有可用的相机");
                return;
            }
            shotLauncher.launch(intent);
        } catch (Throwable e) {
            pendingShotFile = null;
            pendingShotUri = null;
            toast("打开相机失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private boolean canUpload() {
        if (uploading) {
            toast("上一段还在上传，等它传完");
            return false;
        }
        if (!prefs.bridgeMode() || prefs.bridgeUrl().isEmpty()) {
            toast("先在设置里切到「电脑 Harness」并填好地址");
            return false;
        }
        return true;
    }

    private void doUpload(File file, String name, String kind) {
        try {
            JSONObject result = uploadUnit(file, name, kind);
            final int seq = result.optInt("seq", 0);
            ui.post(() -> {
                uploading = false;
                toast("第 " + seq + " 段已交给电脑，正在识别");
                lastRenderKey = "";
            });
            refreshNow();
        } catch (Throwable e) {
            failUpload(e);
        }
    }

    private void failUpload(Throwable e) {
        final String message = e.getMessage() == null ? e.toString() : e.getMessage();
        ui.post(() -> {
            uploading = false;
            tvSummary.setText("上传失败：" + message);
        });
    }

    private File copyToCache(Uri uri, String name) throws Exception {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(getCacheDir(), "unit-" + System.currentTimeMillis() + "-" + safe);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalStateException("打不开这个文件");
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return target;
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "unit.bin" : fallback;
    }

    /** POST /api/units?taskMode=segment&paperId=xxx&mode=single —— 到货即处理 */
    private JSONObject uploadUnit(File file, String name, String kind) throws Exception {
        String paper = prefs.paperId();
        String encodedName = Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String path = "/api/units?taskMode=segment&paperId=" + Uri.encode(paper) + "&mode=single";

        HttpURLConnection c = (HttpURLConnection) new URL(prefs.bridgeUrl() + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(file.length());
        c.setRequestProperty("Authorization", "Bearer " + prefs.bridgeToken());
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Unit-Kind", kind);
        c.setRequestProperty("X-Unit-Name-B64", encodedName);
        c.setRequestProperty("X-Unit-Paper", paper);

        try (InputStream in = new java.io.FileInputStream(file);
             OutputStream out = c.getOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }

        int code = c.getResponseCode();
        String body = readBody(c, code);
        c.disconnect();
        if (code != 200 && code != 202) throw new IllegalStateException("电脑返回 " + code + "：" + body);
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    // ── 轮询与渲染 ───────────────────────────────────────────────

    private void startPolling() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!polling) return;
                refreshNow();
                ui.postDelayed(this, 4000);
            }
        }, 800);
    }

    private void refreshNow() {
        final String paper = prefs.paperId();
        if (paper == null || paper.isEmpty()) return;
        pool.execute(() -> {
            try {
                JSONObject data = bridgeGet("/api/papers/" + Uri.encode(paper));
                ui.post(() -> render(data));
            } catch (Throwable ignored) {
                // 网络抖动不打扰用户，下一轮再试
            }
        });
    }

    private JSONObject bridgeGet(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.bridgeUrl() + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + prefs.bridgeToken());
        int code = c.getResponseCode();
        String body = readBody(c, code);
        c.disconnect();
        if (code != 200) throw new IllegalStateException("电脑返回 " + code);
        return new JSONObject(body);
    }

    private String readBody(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) return "";
        try (InputStream in = stream; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void renderEmpty() {
        tvPaper.setText("这套卷：" + prefs.paperTitle());
        tvSummary.setText("还没有分段。每加一段，这一段的答案会直接出现在下面。");
        answerList.removeAllViews();
    }

    private void render(JSONObject data) {
        try {
            lastPaper = data;   // 转发用，必须放在早退之前
            JSONObject paper = data.optJSONObject("paper");
            JSONArray units = data.optJSONArray("units");
            JSONObject answers = paper == null ? null : paper.optJSONObject("answers");

            int totalUnits = units == null ? 0 : units.length();
            int doneUnits = 0;
            int failedUnits = 0;
            if (units != null) {
                for (int i = 0; i < units.length(); i++) {
                    String status = units.optJSONObject(i).optString("status", "");
                    if ("done".equals(status)) doneUnits++;
                    if ("failed".equals(status)) failedUnits++;
                }
            }
            int answerCount = answers == null ? 0 : answers.length();

            tvPaper.setText("这套卷：" + prefs.paperTitle());
            StringBuilder summary = new StringBuilder();
            if (totalUnits == 0) {
                summary.append("还没有分段。每加一段，这一段的答案会直接出现在下面。");
            } else {
                summary.append("已完成 ").append(doneUnits).append("/").append(totalUnits).append(" 段");
                if (failedUnits > 0) summary.append("（").append(failedUnits).append(" 段失败）");
                summary.append(" · 累计 ").append(answerCount).append(" 题");
            }
            tvSummary.setText(summary.toString());

            // 内容没变就不重绘，避免滚动位置被打回去
            String key = doneUnits + "/" + totalUnits + "#" + answerCount + "#" + (units == null ? 0 : units.toString().hashCode());
            if (key.equals(lastRenderKey)) return;
            lastRenderKey = key;

            answerList.removeAllViews();

            // 段状态
            if (units != null && units.length() > 0) {
                addSectionTitle("分段进度");
                for (int i = 0; i < units.length(); i++) {
                    JSONObject unit = units.optJSONObject(i);
                    int seq = unit.optInt("seq", i + 1);
                    String status = unit.optString("status", "");
                    String kind = "image".equals(unit.optString("kind")) ? "照片" : "视频";
                    String label = "第 " + seq + " 段 · " + kind + " · " + statusText(status);
                    TextView row = addRow(label, "failed".equals(status) ? unit.optString("error", "") : "");
                    if (!"done".equals(status)) row.setTextColor(0xFF8A8A8A);
                }
            }

            // 累积答案：默认只显示答案本身，解析折叠起来，点一下才展开
            if (answerCount > 0) {
                addSectionTitle("已出答案（" + answerCount + " 题）");
                JSONObject details = paper.optJSONObject("details");
                List<String> ids = new ArrayList<>();
                for (Iterator<String> it = answers.keys(); it.hasNext(); ) ids.add(it.next());
                Collections.sort(ids, (a, b) -> {
                    try {
                        return Integer.compare(Integer.parseInt(a.replaceAll("\\D", "")), Integer.parseInt(b.replaceAll("\\D", "")));
                    } catch (Throwable e) {
                        return a.compareTo(b);
                    }
                });
                for (String id : ids) {
                    addAnswerRow(id, answers.optString(id, ""), details == null ? "" : details.optString(id, ""));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一条答案：答案本体直接显示，解析默认折叠，点「展开」才看 */
    private void addAnswerRow(String id, String answer, String detail) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = dp(12);
        box.setLayoutParams(boxLp);

        // 答案本体：短答案一行；长文本（没有 detail 的老数据）也折起来
        boolean longAnswer = answer != null && answer.length() > 80;
        String head = "第 " + id + " 题　" + (longAnswer ? answer.substring(0, 80) + "…" : answer);
        TextView answerView = new TextView(this);
        answerView.setText(head);
        answerView.setTextSize(15);
        answerView.setTextColor(0xFF1F1F1F);
        answerView.setLineSpacing(dp(4), 1f);
        box.addView(answerView);

        String extra = detail == null ? "" : detail.trim();
        if (extra.isEmpty() && longAnswer) extra = answer;   // 老数据：全文当解析
        if (extra.isEmpty()) {
            answerList.addView(box);
            return;
        }

        TextView toggle = new TextView(this);
        toggle.setText("展开解析");
        toggle.setTextSize(12);
        toggle.setTextColor(0xFF5B6B8C);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.topMargin = dp(4);
        toggle.setLayoutParams(toggleLp);
        box.addView(toggle);

        TextView detailView = new TextView(this);
        detailView.setText(extra);
        detailView.setTextSize(13);
        detailView.setTextColor(0xFF555555);
        detailView.setLineSpacing(dp(3), 1f);
        detailView.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(6);
        detailView.setLayoutParams(detailLp);
        box.addView(detailView);

        final boolean[] open = {false};
        toggle.setOnClickListener(v -> {
            open[0] = !open[0];
            detailView.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            toggle.setText(open[0] ? "收起解析" : "展开解析");
        });

        answerList.addView(box);
    }

    /** 转发：把当前这套卷的答案生成纯文本，走系统分享 */
    private void shareAnswers() {
        JSONObject paper = lastPaper == null ? null : lastPaper.optJSONObject("paper");
        JSONObject answers = paper == null ? null : paper.optJSONObject("answers");
        if (answers == null || answers.length() == 0) {
            toast("还没有答案可以转发");
            return;
        }

        StringBuilder text = new StringBuilder();
        text.append(prefs.paperTitle()).append("（共 ").append(answers.length()).append(" 题）\n\n");
        for (String id : sortedIds(answers)) {
            text.append(id).append(". ").append(answers.optString(id, "")).append('\n');
        }
        text.append("\n—— 由卷灵识别");

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, prefs.paperTitle());
        send.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(send, "转发答案"));
    }

    /** 历史试卷：换过「新卷」之后，从这里切回之前那套 */
    private void showPaperHistory() {
        pool.execute(() -> {
            try {
                JSONObject data = bridgeGet("/api/papers");
                JSONArray papers = data.optJSONArray("papers");
                final List<String> ids = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                if (papers != null) {
                    for (int i = 0; i < papers.length(); i++) {
                        JSONObject item = papers.optJSONObject(i);
                        String id = item.optString("paperId", "");
                        if (id.isEmpty()) continue;
                        int answers = item.optInt("answerCount", 0);
                        int units = item.optInt("unitCount", 0);
                        String updated = item.optString("updatedAt", "");
                        if (updated.length() >= 16) updated = updated.substring(5, 16).replace('T', ' ');
                        String mark = id.equals(prefs.paperId()) ? " ← 当前" : "";
                        ids.add(id);
                        labels.add(id.replace("paper-", "") + "　" + units + " 段 / " + answers + " 题　" + updated + mark);
                    }
                }
                ui.post(() -> {
                    if (ids.isEmpty()) {
                        toast("还没有历史试卷");
                        return;
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("选择要看的试卷")
                            .setItems(labels.toArray(new String[0]), (d, which) -> {
                                prefs.setPaperId(ids.get(which));
                                lastRenderKey = "";
                                lastPaper = null;
                                renderEmpty();
                                refreshNow();
                                toast("已切到这套卷");
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Throwable e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> toast("读取历史失败：" + message));
            }
        });
    }

    /** 题号排序：数字优先按数值，非数字按字典序 */
    private List<String> sortedIds(JSONObject answers) {
        List<String> ids = new ArrayList<>();
        for (Iterator<String> it = answers.keys(); it.hasNext(); ) ids.add(it.next());
        Collections.sort(ids, (a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a.replaceAll("\\D", "")), Integer.parseInt(b.replaceAll("\\D", "")));
            } catch (Throwable e) {
                return a.compareTo(b);
            }
        });
        return ids;
    }

    private String statusText(String status) {
        switch (status == null ? "" : status) {
            case "done": return "已完成";
            case "running": return "识别中";
            case "failed": return "失败";
            case "pending": return "排队中";
            default: return status;
        }
    }

    private void addSectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(13);
        title.setTextColor(0xFF6B6B6B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(6);
        title.setLayoutParams(lp);
        answerList.addView(title);
    }

    private TextView addRow(String left, String right) {
        TextView row = new TextView(this);
        String text = right == null || right.isEmpty() ? left : left + "　" + right;
        row.setText(text);
        row.setTextSize(14);
        row.setTextColor(0xFF1F1F1F);
        row.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        answerList.addView(row);
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
