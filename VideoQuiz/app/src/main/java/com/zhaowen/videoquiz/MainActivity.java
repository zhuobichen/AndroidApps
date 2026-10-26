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
    private final Map<String, Integer> variantConflicts = new java.util.HashMap<>();

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
                final java.util.Set<Integer> seen = new java.util.HashSet<>();
                final Map<Integer, Map<String, Integer>> stemVotes = new java.util.HashMap<>();
                final Map<Integer, Map<String, String>> stemText = new java.util.HashMap<>();
                final Map<Integer, Map<String, Integer>> optVotes = new java.util.HashMap<>();
                final Map<Integer, Map<String, List<String>>> optList = new java.util.HashMap<>();
                final Map<Integer, Map<String, Integer>> stemFrame = new java.util.HashMap<>();
                final Map<Integer, Map<Integer, List<String>>> optByFrame = new java.util.HashMap<>();
                final Map<Integer, String> blankBest = new java.util.HashMap<>();
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
                            q.firstFrame = idx;
                            q.sharp = sharp;
                            seen.add(q.no);
                            if (q.blankSentence != null && q.blankSentence.length() > 20
                                    && q.blankSentence.matches("(?s).*\\b" + q.no + "\\s*_{2,}.*")) {
                                String oldB = blankBest.get(q.no);
                                if (oldB == null || q.blankSentence.length() > oldB.length()) {
                                    blankBest.put(q.no, q.blankSentence);
                                }
                            }
                            // 题干单独投票（空题干不参与）
                            String ns = q.stem.replaceAll("\\s+", " ").trim();
                            if (!ns.isEmpty()) {
                                stemVotes.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .merge(ns, 1, Integer::sum);
                                stemText.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .putIfAbsent(ns, q.stem);
                                stemFrame.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .putIfAbsent(ns, idx);
                            }
                            // 选项单独投票
                            if (q.options.size() >= 2) {
                                String os = String.join(";", q.options);
                                optVotes.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .merge(os, 1, Integer::sum);
                                optList.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .putIfAbsent(os, q.options);
                                optByFrame.computeIfAbsent(q.no, k -> new java.util.HashMap<>())
                                        .putIfAbsent(idx, q.options);
                            }
                            if (!map.containsKey(q.no)) { map.put(q.no, q); newOne = true; }
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

                // 按投票结果重建每道题：题干取票数最高，选项取票数最高
                for (Integer no : seen) {
                    Question q = map.get(no);
                    if (q == null) continue;
                    Map<String, Integer> sv = stemVotes.get(no);
                    if (sv != null && !sv.isEmpty()) {
                        String best = null; int bestV = -1;
                        for (Map.Entry<String, Integer> e : sv.entrySet()) {
                            if (e.getValue() > bestV) { bestV = e.getValue(); best = e.getKey(); }
                        }
                        // 取出现次数最多的版本（宁缺勿错：不做跨版本拼接）
                        q.stem = stemText.get(no).get(best);
                    }
                    // 选项优先取「题干所在帧」的，避免串到别的题
                    boolean got = false;
                    Integer f = null;
                    if (q.stem != null) {
                        Map<String, Integer> sf = stemFrame.get(no);
                        if (sf != null) f = sf.get(q.stem.replaceAll("\\s+", " ").trim());
                    }
                    if (f != null) {
                        Map<Integer, List<String>> of = optByFrame.get(no);
                        if (of != null && of.get(f) != null) { q.options = of.get(f); got = true; }
                    }
                    if (!got) {
                        Map<String, Integer> ov = optVotes.get(no);
                        if (ov != null && !ov.isEmpty()) {
                            String best = null; int bestV = -1;
                            for (Map.Entry<String, Integer> e : ov.entrySet()) {
                                if (e.getValue() > bestV) { bestV = e.getValue(); best = e.getKey(); }
                            }
                            q.options = optList.get(no).get(best);
                        }
                    }
                }

                // 空白题（题干里没字母）：先本地回填，剩下的交给模型按原文补全
                List<Integer> needCtx = new ArrayList<>();
                for (Question q : map.values()) {
                    if (q.stem.contains("___") && !q.stem.startsWith("（第")) {
                        needCtx.add(q.no);
                    }
                }
                if (!needCtx.isEmpty() && !paras.isEmpty()) {
                    StringBuilder allP = new StringBuilder();
                    for (Para p : paras) allP.append(p.text).append("\n\n");
                    StringBuilder nos = new StringBuilder();
                    for (int no : needCtx) nos.append(no).append(' ');
                    try {
                        String reply = client.chat(
                                "你是英语试卷排版助手。只输出 JSON。",
                                "原文：\n" + allP + "\n\n请为下列每个空号，给出它所在的那一整句（照抄原文，不要改写，"
                                        + "包含空格前后的内容）：\n" + nos
                                        + "\n输出 JSON：{\"36\":\"该空所在的完整句子\", ...}");
                        JSONObject o = new JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1));
                        for (int no : needCtx) {
                            String s = o.optString(String.valueOf(no), "").trim();
                            Question q = map.get(no);
                            if (q != null && s.length() > 20) q.stem = s;
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("VideoQuiz", "context fill failed: " + t.getMessage());
                    }
                }

                // 完形选项表可能没有题干，补占位，避免 PDF 里空着
                for (Question q : map.values()) {
                    if (q.stem.isEmpty()) q.stem = "（第 " + q.no + " 题，题干见原文）";
                    q.stem = trimToOwnBlank(q.no, q.stem);
                    // 清理题干：去掉串入的「【原文】」标记及其后的内容
                    int cut = q.stem.indexOf("【原");
                    if (cut > 0) q.stem = q.stem.substring(0, cut).trim();
                    q.stem = q.stem.replaceAll("\\s+", " ").trim();

                    // 题干里没有字母 = 只是横线标记（如 "36 __________."），从原文回填所在句
                    if (!q.stem.matches(".*[A-Za-z]{3,}.*")) {
                        String ctx = sentenceWithBlank(q.no);
                        if (ctx != null) q.stem = ctx;
                        else android.util.Log.w("VideoQuiz", "no context for q" + q.no);
                    }
                }

                lastTotalFrames = totalFrames[0];
                lastKeptFrames = kept;

                // 空格题：优先用模型返回的 blank_sentence（含空前空后），它比拼接更可靠
                for (Map.Entry<Integer, String> e : blankBest.entrySet()) {
                    Question q = map.get(e.getKey());
                    if (q == null) continue;
                    String s = e.getValue().replaceAll("\\s+", " ").trim();
                    if (s.matches("(?s).*\\b" + e.getKey() + "\\s*_{2,}.*")) q.stem = s;
                }

                // 收尾再清一遍题干：去掉串入的「【原文】」和后续内容
                for (Question q : map.values()) {
                    int k = q.stem.indexOf("【原");
                    if (k > 0) q.stem = q.stem.substring(0, k);
                    q.stem = q.stem.replaceAll("\\s+", " ").trim();
                }

                // 统计「同一题号出现几个不同版本」
                // 只有「实质不同」（相似度 < 0.85）才算版本冲突，OCR 字面差异不算
                variantConflicts.clear();
                for (Map.Entry<Integer, Map<String, Integer>> e : stemVotes.entrySet()) {
                    List<String> clusters = new ArrayList<>();
                    for (String k : e.getValue().keySet()) {
                        boolean dup = false;
                        for (String c0 : clusters) {
                            if (jaccard(k.toLowerCase(), c0.toLowerCase()) >= 0.85) { dup = true; break; }
                        }
                        if (!dup) clusters.add(k);
                    }
                    if (clusters.size() > 1) {
                        variantConflicts.put(String.valueOf(e.getKey()), clusters.size());
                    }
                }

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
                        List<String> conf = confidenceReport();
                        String head = conf.isEmpty() ? "" : " · 置信度 " + conf.get(0).replace("识别置信度：", "");
                        status("识别到 " + sorted.size() + " 题（读了 " + kept + " 个清晰帧）"
                                + (gaps.isEmpty() ? "" : " · 缺题 " + gaps + "（可能不在视频里）")
                                + head);
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
            if (t.length() < 12) continue;
            String nt = t.replaceAll("\\s+", "").toLowerCase();
            boolean dup = false;
            int replaceIdx = -1;
            Set<String> newLines = new LinkedHashSet<>();
            for (String ln : t.split("\n")) {
                String k = ln.replaceAll("\\s+", "").toLowerCase();
                if (k.length() > 12) newLines.add(k);
            }
            for (int i = 0; i < paras.size(); i++) {
                Para e = paras.get(i);
                String ne = e.text.replaceAll("\\s+", "").toLowerCase();
                // 新段包含旧段 → 用更完整的新段替换，避免开头被截掉
                if (nt.contains(ne) && nt.length() > ne.length()) { replaceIdx = i; break; }
                if (ne.contains(nt)) { dup = true; break; }
                if (jaccard(nt, ne) >= 0.7) { dup = true; break; }
            }
            if (replaceIdx >= 0) paras.set(replaceIdx, new Para(paras.get(replaceIdx).frame, t));
            else if (!dup) paras.add(new Para(frame, t));
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
        int bestScore = -1;
        for (int i = 0; i < sentences.length; i++) {
            String t = sentences[i].trim();
            if (t.length() < 8 || t.length() > 400) continue;
            if (!pat.matcher(t).find()) continue;
            int score = t.replaceAll("[^A-Za-z]", "").length();   // 字母越多，句子越完整
            if (score > bestScore) { bestScore = score; best = t; bestIdx = i; }
        }
        // 空格在句首/句末时，把相邻那句也拼上，给足上下文
        if (best != null && bestIdx > 0) {
            String prev = sentences[bestIdx - 1].trim();
            boolean startsWithBlank = best.matches("^\\s*\\d+\\s*_{2,}.*");
            if ((startsWithBlank || best.length() < 60) && prev.length() > 15) {
                best = prev + " " + best;
            }
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

    private static double clamp100(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** 整体置信度：清晰度 35% + 覆盖 30% + 完整度 25% + 帧质量 10% */
    private List<String> confidenceReport() {
        List<String> out = new ArrayList<>();
        if (questions.isEmpty()) return out;

        List<Double> sh = new ArrayList<>();
        for (Question q : questions) if (q.sharp > 0) sh.add(q.sharp);
        java.util.Collections.sort(sh);
        double med = sh.isEmpty() ? 0 : sh.get(sh.size() / 2);
        double sharpScore = clamp100((med - 300) / (2500 - 300) * 100);

        java.util.TreeSet<Integer> have = new java.util.TreeSet<>();
        for (Question q : questions) have.add(q.no);
        int lo = have.first(), hi = have.last();
        int span = Math.max(1, hi - lo + 1);
        double coverage = clamp100(have.size() * 100.0 / span);

        int ok = 0;
        StringBuilder bad = new StringBuilder();
        for (Question q : questions) {
            String s = q.stem == null ? "" : q.stem.trim();
            boolean hasStem = !s.startsWith("（第") && s.length() >= 25
                    && !s.matches("^\\d*\\s*_{2,}\\s*\\.?$");
            boolean hasBody = q.options.size() >= 2 || s.contains("___");
            if (hasStem && hasBody) ok++;
            else if (bad.length() < 60) bad.append(bad.length() > 0 ? "、" : "").append(q.no);
        }
        double complete = clamp100(ok * 100.0 / questions.size());
        double frameScore = lastTotalFrames > 0
                ? clamp100(lastKeptFrames * 100.0 / lastTotalFrames) : 100;

        double base = 0.30 * sharpScore + 0.25 * coverage + 0.35 * complete + 0.10 * frameScore;
        int incomplete = questions.size() - ok;
        double total = clamp100(base - 2.0 * incomplete);   // 每题不完整扣 2 分
        String grade = total >= 95 ? "优" : total >= 85 ? "良" : total >= 70 ? "一般" : "较差";
        out.add("识别置信度：" + Math.round(total) + "/100（" + grade + "）");
        out.add("画面清晰度 " + Math.round(sharpScore) + "/100（题目来源帧中位清晰度 "
                + Math.round(med) + "）");
        out.add("题目完整度 " + Math.round(complete) + "/100（" + questions.size()
                + " 题里 " + ok + " 题题干与选项齐全" + (bad.length() > 0 ? "；不完整：" + bad : "") + "）");
        out.add("覆盖完整度 " + Math.round(coverage) + "/100（" + lo + "-" + hi
                + " 区间实到 " + have.size() + " 题）");
        out.add("帧质量 " + Math.round(frameScore) + "/100（" + lastKeptFrames + "/"
                + lastTotalFrames + " 帧可用）");
        return out;
    }

    /** 题干里是否出现了别的题号（粘题检测） */
    private static boolean hasOtherNumber(String s, int self) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)(^|\\s)(\\d{1,2})\\s*[.、．]").matcher(s);
        while (m.find()) {
            int n = Integer.parseInt(m.group(2));
            if (n >= 1 && n <= 99 && n != self) return true;
        }
        return false;
    }

    /** 去掉空格和 $ 后比较，避免 LaTeX 排版差异导致「同一题」判不出来 */
    private static String normStem(String s) {
        return s.replaceAll("[\\s$]", "");
    }

    private static int countSubs(String s) {
        int n = 0;
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\(\\d\\)").matcher(s);
        while (mm.find()) n++;
        return n;
    }

    /** 生成识别质量提示：画面模糊、选项缺失、题干缺失 */
    private List<String> qualityWarnings() {
        List<String> w = new ArrayList<>();
        if (lastTotalFrames > 0) {
            int blurry = Math.max(0, lastTotalFrames - lastKeptFrames);
            w.add("共抽取 " + lastTotalFrames + " 帧，清晰可用 " + lastKeptFrames + " 帧"
                    + (blurry > 0 ? "，另有 " + blurry + " 帧因画面模糊被丢弃" : ""));
        }
        for (Map.Entry<String, Integer> e : variantConflicts.entrySet()) {
            w.add("第 " + e.getKey() + " 题在不同帧里出现了不同版本（" + e.getValue()
                    + " 个），已取出现次数最多的一个，建议核对");
        }
        for (Question q : questions) {
            if (q.sharp > 0 && q.sharp < 1500) {
                w.add("第 " + q.no + " 题来源画面偏模糊（清晰度 " + Math.round(q.sharp)
                        + "），建议核对或重拍这一段");
            }
            if (q.stem.startsWith("（第")) {
                w.add("第 " + q.no + " 题只读到选项、没读到题干");
            }
            // 只有「应该带选项」的题才提示：21-35 阅读、41-55 完形
            boolean shouldHaveOptions = (q.no >= 21 && q.no <= 35) || (q.no >= 41 && q.no <= 55);
            if (shouldHaveOptions && q.options.isEmpty() && q.stem.length() > 10) {
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
        boolean answered = false;
        for (Question q : questions) if (!q.answer.isEmpty()) { answered = true; break; }
        if (!answered) {
            status("先点「解题」，再导出（答案会是空的）");
            return;
        }
        setBusy(true);
        status("正在生成两份 PDF…");
        new Thread(() -> {
            try {
                List<PdfExporter.Block> blocks = buildBlocks();
                List<String> warn = qualityWarnings();
                List<String> conf = confidenceReport();
                File paper = PdfExporter.exportPaper(getFilesDir(), blocks, warn, writings, conf);
                File answers = PdfExporter.exportAnswers(getFilesDir(), questions, writings, essays);
                lastPdf = paper;
                ui.post(() -> {
                    status("已生成：" + paper.getName() + " 和 " + answers.getName());
                    setBusy(false);
                    sharePdfs(paper, answers);
                });
            } catch (Throwable e) {
                status("生成失败：" + e.getMessage());
                setBusy(false);
            }
        }).start();
    }

    /** 按视频顺序把「原文段落」和「题目」排成时间线 */
    private List<PdfExporter.Block> buildBlocks() {
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
        // 按「块内最小题号」排序，保证卷面题号递增；无题目的原文块排在其后一题之前
        int n = blocks.size();
        int[] key = new int[n];
        int nextQ = 1000;
        for (int i = n - 1; i >= 0; i--) {
            PdfExporter.Block b = blocks.get(i);
            int min = 1000;
            for (Question q : b.questions) min = Math.min(min, q.no);
            key[i] = min < 1000 ? min : nextQ;
            if (min < 1000) nextQ = min;
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            if (key[a] != key[b]) return Integer.compare(key[a], key[b]);
            return Integer.compare(a, b);
        });
        List<PdfExporter.Block> sorted = new ArrayList<>();
        for (int i : idx) sorted.add(blocks.get(i));
        for (PdfExporter.Block b : sorted) {
            b.questions.sort((x, y) -> Integer.compare(x.no, y.no));
        }
        return sorted;
    }

    private void sharePdfs(File paper, File answers) {
        try {
            Uri u1 = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", paper);
            Uri u2 = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", answers);
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("application/pdf");
            ArrayList<Uri> list = new ArrayList<>();
            list.add(u1);
            list.add(u2);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享试卷和答案"));
        } catch (Throwable e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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