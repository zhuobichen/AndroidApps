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
    private final List<Para> paras = new ArrayList<>();
    private final List<String> writings = new ArrayList<>();
    private final List<String> essays = new ArrayList<>();

    /** 一段原文，记录它出现在第几帧（用于还原试卷顺序） */
    private static class Para {
        int frame;
        String text;
        Para(int f, String t) { frame = f; text = t; }
    }
    private File lastPdf;
    private boolean busy = false;
    private int lastTotalFrames = 0;
    private int lastKeptFrames = 0;

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
                final int[] totalFrames = {0};

                int kept = FrameSampler.sample(this, videoUri, 2, 120, (bmp, t, sharp, idx, total) -> {
                    totalFrames[0] = total;
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
                                q.firstFrame = idx;
                                q.sharp = sharp;
                                map.put(q.no, q);
                                newOne = true;
                            } else {
                                // 题干取更长的，选项取更多的，两者合并而不是整体替换
                                if (q.stem.length() > old.stem.length()) old.stem = q.stem;
                                if (q.options.size() > old.options.size()) old.options = q.options;
                            }
                        }
                        for (String wr : r.writings) {
                            boolean dupW = false;
                            for (String oldW : writings) {
                                if (jaccard(wr.replaceAll("\\s+", ""), oldW.replaceAll("\\s+", "")) >= 0.45) {
                                    dupW = true; break;
                                }
                            }
                            if (!dupW && writings.size() < 4) writings.add(wr);
                        }
                        if (r.passage != null && !r.passage.trim().isEmpty()) {
                            passages.add(r.passage.trim());
                            addParagraph(r.passage, idx);
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

                // 完形选项表可能没有题干，补占位，避免 PDF 里空着
                for (Question q : map.values()) {
                    if (q.stem.isEmpty()) q.stem = "（第 " + q.no + " 题，题干见原文）";
                    q.stem = trimToOwnBlank(q.no, q.stem);
                    // 只有横线（如 36______）的题，从原文里找回它所在的那句话
                    if (q.stem.matches("^\\d+\\s*_{2,}\\s*\\.?$")) {
                        String ctx = sentenceWithBlank(q.no);
                        if (ctx != null) q.stem = ctx;
                    }
                }

                lastTotalFrames = totalFrames[0];
                lastKeptFrames = kept;

                // 作文只保留「应用文」「续写」两篇：按长度排序后取互不相似的前两条
                if (writings.size() > 2) {
                    List<String> sortedW = new ArrayList<>(writings);
                    sortedW.sort((a, b) -> Integer.compare(b.length(), a.length()));
                    List<String> keep = new ArrayList<>();
                    for (String w : sortedW) {
                        boolean dup = false;
                        for (String k : keep) {
                            if (jaccard(w.replaceAll("\\s+", ""), k.replaceAll("\\s+", "")) >= 0.35) {
                                dup = true; break;
                            }
                        }
                        if (!dup) keep.add(w);
                        if (keep.size() >= 2) break;
                    }
                    writings.clear();
                    writings.addAll(keep);
                }

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
                        String gaps = findGaps(sorted);
                        status("识别到 " + sorted.size() + " 题（读了 " + kept + " 个清晰帧）"
                                + (gaps.isEmpty() ? "" : " · 缺题 " + gaps + "（可能不在视频里）"));
                    }
                    setBusy(false);
                });
            } catch (Throwable e) {
                status("识别失败：" + e.getMessage());
                setBusy(false);
            }
        }).start();
    }

    /** 把一页的正文按段落切开，去重后按首次出现顺序保存 */
    private void addParagraph(String pageText, int frame) {
        String[] parts = pageText.split("\\n\\s*\\n");
        for (String p : parts) {
            String t = p.trim();
            if (t.length() < 30) continue;
            String nt = t.replaceAll("\\s+", "").toLowerCase();
            boolean dup = false;
            Set<String> newLines = new LinkedHashSet<>();
            for (String ln : t.split("\n")) {
                String k = ln.replaceAll("\\s+", "").toLowerCase();
                if (k.length() > 12) newLines.add(k);
            }
            for (Para e : paras) {
                String ne = e.text.replaceAll("\\s+", "").toLowerCase();
                if (ne.contains(nt) || nt.contains(ne)) { dup = true; break; }
                if (jaccard(nt, ne) >= 0.7) { dup = true; break; }
            }
            if (!dup) paras.add(new Para(frame, t));
        }
    }

    /** 字符三元组 Jaccard，用来判断两段文字是不是同一段 */
    private static double jaccard(String a, String b) {
        java.util.Set<String> sa = grams(a), sb = grams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int inter = 0;
        for (String g : sa) if (sb.contains(g)) inter++;
        return inter / (double) (sa.size() + sb.size() - inter);
    }

    private static java.util.Set<String> grams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        return out;
    }

    /** 从已识别的原文里，找出包含「题号 + 横线」的那一句 */
    private String sentenceWithBlank(int no) {
        StringBuilder all = new StringBuilder();
        for (Para p : paras) all.append(p.text).append("\n");
        String text = all.toString();
        if (text.isEmpty()) return null;
        String[] sentences = text.split("(?<=[.!?])\\s+|\\n+");
        java.util.regex.Pattern pat = java.util.regex.Pattern
                .compile("(?<![0-9])" + no + "\\s*_{2,}");
        String best = null;
        int bestIdx = -1;
        for (int i = 0; i < sentences.length; i++) {
            String t = sentences[i].trim();
            if (t.length() < 8 || t.length() > 400) continue;
            if (pat.matcher(t).find()) {
                if (best == null || t.length() < best.length()) { best = t; bestIdx = i; }
            }
        }
        // 空格在句末时会单独成句，把上一句拼回来
        if (best != null && best.length() < 60 && bestIdx > 0) {
            String prev = sentences[bestIdx - 1].trim();
            if (prev.length() > 15) best = prev + " " + best;
        }
        return best;
    }

    /** 一句里可能有两个空格（51/52），只保留包含本题空格的那一句 */
    static String trimToOwnBlank(int no, String stem) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![0-9])" + no + "\\s*_{2,}").matcher(stem);
        if (!m.find()) return stem;
        String[] parts = stem.split("(?<=[.!?])\\s+");
        if (parts.length <= 1) return stem;
        for (String p : parts) {
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("(?<![0-9])" + no + "\\s*_{2,}").matcher(p);
            if (mm.find()) return p.trim();
        }
        return stem;
    }

    /** 生成识别质量提示：画面模糊、选项缺失、题干缺失 */
    private List<String> qualityWarnings() {
        List<String> w = new ArrayList<>();
        if (lastTotalFrames > 0) {
            int blurry = Math.max(0, lastTotalFrames - lastKeptFrames);
            w.add("共抽取 " + lastTotalFrames + " 帧，清晰可用 " + lastKeptFrames + " 帧"
                    + (blurry > 0 ? "，另有 " + blurry + " 帧因画面模糊被丢弃" : ""));
        }
        for (Question q : questions) {
            if (q.sharp > 0 && q.sharp < 1500) {
                w.add("第 " + q.no + " 题来源画面偏模糊（清晰度 " + Math.round(q.sharp)
                        + "），建议核对或重拍这一段");
            }
            if (q.stem.startsWith("（第")) {
                w.add("第 " + q.no + " 题只读到选项、没读到题干");
            }
            if (q.no >= 21 && q.options.isEmpty() && q.stem.length() > 10
                    && !q.stem.contains("___") && !q.stem.contains("______")) {
                w.add("第 " + q.no + " 题没有读到选项");
            }
        }
        return w;
    }

    /** 找出题号区间的空缺，如 41-48 */
    private String findGaps(List<Question> qs) {
        if (qs.size() < 2) return "";
        java.util.TreeSet<Integer> have = new java.util.TreeSet<>();
        for (Question q : qs) have.add(q.no);
        int lo = 1, hi = have.last();
        StringBuilder sb = new StringBuilder();
        int start = -1;
        for (int n = lo; n <= hi + 1; n++) {
            boolean missing = (n <= hi) && !have.contains(n);
            if (missing && start < 0) start = n;
            if (!missing && start >= 0) {
                if (sb.length() > 0) sb.append("、");
                sb.append(start == n - 1 ? String.valueOf(start) : start + "-" + (n - 1));
                start = -1;
            }
        }
        return sb.toString();
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
                        body + "\n输出 JSON 数组，每项 {\"no\":题号,\"answer\":\"...\",\"reason\":\"...\"}。规则：\n"
                             + "1. 选择题：answer 填选项字母（如 B），reason 填一句中文理由；\n"
                             + "2. 完形/语法填空：answer 填空格应填的单词，reason 填一句中文理由；\n"
                             + "3. 写作题（没有选项、题号通常小于 20）：answer 填「范文」，reason 给出"
                             + "写作思路（一句中文）+ 一篇 80-120 词的英文范文。\n"
                             + "所有题目都要给，不要漏。");

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
                // 作文：逐篇给思路 + 范文
                essays.clear();
                for (String w : writings) {
                    try {
                        String e = client.chat(
                                "你是高中英语老师，回答只给正文，不要客套。",
                                "作文题目：\n" + w + "\n\n请输出：\n第一行：一句中文写作思路；\n"
                                        + "然后一篇 80-120 词的英文范文。");
                        essays.add(e.trim());
                    } catch (Throwable t) {
                        essays.add("");
                    }
                }

                final int f = filled;
                ui.post(() -> {
                    render();
                    status("已解出 " + f + " 题" + (writings.isEmpty() ? "" : " · 作文范文 " + essays.size() + " 篇"));
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
                // 按视频顺序把「原文段落」和「题目」排成时间线，
                // 连续段落合并成一块，题目单独成块
                List<Object[]> events = new ArrayList<>();
                for (Para p : paras) events.add(new Object[]{p.frame, 0, p.text});
                for (Question q : questions) events.add(new Object[]{q.firstFrame, 1, q});
                events.sort((a, b) -> {
                    int fa = (int) a[0], fb = (int) b[0];
                    if (fa != fb) return Integer.compare(fa, fb);
                    return Integer.compare((int) a[1], (int) b[1]);
                });

                List<PdfExporter.Block> blocks = new ArrayList<>();
                PdfExporter.Block cur = null;
                for (Object[] e : events) {
                    int kind = (int) e[1];
                    if (kind == 0) {
                        if (cur == null || !cur.questions.isEmpty()) {
                            cur = new PdfExporter.Block();
                            cur.frame = (int) e[0];
                            blocks.add(cur);
                        }
                        String t = (String) e[2];
                        cur.passage = cur.passage == null ? t : cur.passage + "\n\n" + t;
                    } else {
                        if (cur == null || cur.passage != null) {
                            cur = new PdfExporter.Block();
                            cur.frame = (int) e[0];
                            blocks.add(cur);
                        }
                        cur.questions.add((Question) e[2]);
                    }
                }

                List<String> warn = qualityWarnings();
                File f = PdfExporter.export(getFilesDir(), blocks, warn, writings, essays, true);
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