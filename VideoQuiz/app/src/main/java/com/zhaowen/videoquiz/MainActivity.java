package com.zhaowen.videoquiz;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Uri videoUri;
    private final List<Question> questions = new ArrayList<>();
    private final Set<String> passages = new LinkedHashSet<>();
    private File lastPdf;
    private boolean busy = false;

    private TextView tvVideo, tvStatus, tvResult;
    private ProgressBar progress;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                videoUri = uri;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                tvVideo.setText("已选择：" + uri.getLastPathSegment());
                findViewById(R.id.btnRun).setEnabled(true);
                status("准备就绪，点「开始识别」");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        tvVideo = findViewById(R.id.tvVideo);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        progress = findViewById(R.id.progress);

        findViewById(R.id.btnPick).setOnClickListener(v -> picker.launch(new String[]{"video/*"}));
        findViewById(R.id.btnRun).setOnClickListener(v -> run());
        findViewById(R.id.btnSolve).setOnClickListener(v -> solve());
        findViewById(R.id.btnPdf).setOnClickListener(v -> makePdf());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());

        String dbg = getIntent().getStringExtra("video");
        if (dbg != null) {
            videoUri = Uri.fromFile(new File(dbg));
            tvVideo.setText("已选择：" + dbg);
            findViewById(R.id.btnRun).setEnabled(true);
            if (getIntent().hasExtra("autorun")) {
                new Handler(Looper.getMainLooper()).postDelayed(this::run, 600);
            }
        }

        if (prefs.key().isEmpty()) status("先在「接口设置」里填 API Key");
    }

    private void status(String s) {
        ui.post(() -> tvStatus.setText(s));
    }

    private void setBusy(boolean b) {
        busy = b;
        ui.post(() -> {
            progress.setVisibility(b ? View.VISIBLE : View.GONE);
            findViewById(R.id.btnRun).setEnabled(!b && videoUri != null);
            findViewById(R.id.btnSolve).setEnabled(!b && !questions.isEmpty());
            findViewById(R.id.btnPdf).setEnabled(!b && !questions.isEmpty());
        });
    }

    // ---------- 识别：抽帧 → 逐帧送模型 ----------

    private void run() {
        if (busy || videoUri == null) return;
        if (prefs.key().isEmpty()) {
            Toast.makeText(this, "先填 API Key", Toast.LENGTH_SHORT).show();
            showSettings();
            return;
        }
        setBusy(true);
        questions.clear();
        passages.clear();
        progress.setProgress(0);

        new Thread(() -> {
            VisionClient client = new VisionClient(prefs.base(), prefs.key(), prefs.model());
            try {
                final int[] added = {0};
                final int[] emptyRun = {0};
                final int[] skipped = {0};
                final Map<Integer, Question> map = new LinkedHashMap<>();
                final float[][] lastFp = {null};

                int kept = FrameSampler.sample(this, videoUri, 2, 120, (bmp, t, sharp, idx, total) -> {
                    try {
                        // 静止帧过滤：和上一帧几乎一样就不花钱
                        float[] fp = Fingerprint.of(bmp);
                        if (lastFp[0] != null
                                && Fingerprint.similarity(lastFp[0], fp) >= Fingerprint.SAME_FRAME) {
                            skipped[0]++;
                            bmp.recycle();
                            return true;
                        }
                        lastFp[0] = fp;

                        VisionClient.Result r = client.readFrame(bmp);
                        android.util.Log.i("VideoQuiz", "frame " + idx + " sharp=" + (int) sharp
                                + " q=" + r.questions.size() + " passage=" + r.passage.length());
                        boolean newOne = false;
                        for (Question q : r.questions) {
                            Question old = map.get(q.no);
                            if (old == null) {
                                map.put(q.no, q);
                                newOne = true;
                            } else if (q.options.size() > old.options.size()
                                    || q.stem.length() > old.stem.length()) {
                                map.put(q.no, q);
                            }
                        }
                        if (r.passage != null && !r.passage.trim().isEmpty()) {
                            passages.add(r.passage.trim());
                        }
                        if (newOne) { added[0]++; emptyRun[0] = 0; } else { emptyRun[0]++; }
                        final int n = map.size();
                        final int done = idx;
                        ui.post(() -> {
                            progress.setProgress(Math.min(95, done * 95 / Math.max(1, total)));
                            tvStatus.setText("已读 " + done + " 帧（跳过 " + skipped[0]
                                    + " 个重复）· 识别到 " + n + " 题");
                        });
                        return true;   // 一直读到视频结束（帧数已由 maxFrames 限制）
                    } catch (Exception e) {
                        android.util.Log.w("VideoQuiz", "frame " + idx + " failed: " + e.getMessage());
                        return true;
                    } finally {
                        bmp.recycle();
                    }
                }, (d, t) -> {});

                List<Question> sorted = new ArrayList<>(map.values());
                sorted.sort((a, b) -> Integer.compare(a.no, b.no));

                ui.post(() -> {
                    questions.clear();
                    questions.addAll(sorted);
                    progress.setProgress(100);
                    if (sorted.isEmpty()) {
                        tvResult.setText("没有识别到题目。可能是视频太糊或画面里没有题目。\n\n清晰帧：" + kept);
                        status("识别失败");
                    } else {
                        render();
                        status("识别到 " + sorted.size() + " 题（读了 " + kept + " 个清晰帧）");
                    }
                    setBusy(false);
                });
            } catch (Throwable e) {
                status("识别失败：" + e.getMessage());
                setBusy(false);
            }
        }).start();
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        for (Question q : questions) sb.append(q.toDisplay()).append('\n');
        sb.append("—— 共 ").append(questions.size()).append(" 题 ——");
        tvResult.setText(sb.toString());
    }

    // ---------- 解题 ----------

    private void solve() {
        if (busy || questions.isEmpty()) return;
        setBusy(true);
        status("正在解题…");
        new Thread(() -> {
            try {
                VisionClient client = new VisionClient(prefs.base(), prefs.key(), prefs.model());
                StringBuilder body = new StringBuilder();
                if (!passages.isEmpty()) {
                    body.append("【文章正文】\n").append(String.join("\n", passages)).append("\n\n");
                }
                for (Question q : questions) {
                    body.append(q.no).append(". ").append(q.stem).append('\n');
                    for (String o : q.options) body.append(o).append('\n');
                    body.append('\n');
                }
                String reply = client.chat(
                        "你是英语老师。依据给出的原文作答，只输出 JSON 数组。",
                        body + "\n请输出 JSON 数组，每项 {\"no\":题号,\"answer\":\"选项字母\",\"reason\":\"一句话中文理由\"}。");

                JSONArray arr = SolverClient.extractJson(reply);
                int filled = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    int no = VisionClient.toInt(o.opt("no"));
                    for (Question q : questions) {
                        if (q.no == no) {
                            q.answer = o.optString("answer", "");
                            q.reason = o.optString("reason", "");
                            filled++;
                        }
                    }
                }
                final int f = filled;
                ui.post(() -> {
                    render();
                    status("已解出 " + f + " 题");
                    setBusy(false);
                });
            } catch (Throwable e) {
                status("解题失败：" + e.getMessage());
                setBusy(false);
            }
        }).start();
    }

    // ---------- PDF ----------

    private void makePdf() {
        if (questions.isEmpty()) return;
        setBusy(true);
        status("正在生成 PDF…");
        new Thread(() -> {
            try {
                File f = PdfExporter.export(getFilesDir(), questions, true);
                lastPdf = f;
                ui.post(() -> {
                    status("已生成：" + f.getName());
                    setBusy(false);
                    sharePdf(f);
                });
            } catch (Throwable e) {
                status("生成失败：" + e.getMessage());
                setBusy(false);
            }
        }).start();
    }

    private void sharePdf(File f) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "打开或分享 PDF"));
        } catch (Throwable e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 设置 ----------

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText eBase = field(box, "接口地址", prefs.base());
        EditText eKey = field(box, "API Key", prefs.key());
        EditText eModel = field(box, "模型名", prefs.model());

        new MaterialAlertDialogBuilder(this)
                .setTitle("接口设置")
                .setMessage("默认 DeepSeek（deepseek-chat，支持读图）。也可以填其它 OpenAI 兼容服务。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.save(eBase.getText().toString(), eKey.getText().toString(),
                            eModel.getText().toString());
                    status("已保存：" + prefs.model());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        parent.addView(e, lp);
        return e;
    }
}