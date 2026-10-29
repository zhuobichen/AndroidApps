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
import android.widget.ScrollView;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
    private final List<String> pages = new ArrayList<>();

    /** 一段原文，记录它出现在第几帧（用于还原试卷顺序） */
    private static class Para {
        int frame;
        String text;
        Para(int f, String t) { frame = f; text = t; }
    }
    private File lastPdf;
    private boolean busy = false;
    private volatile boolean bridgeCancelled = false;
    private String activeBridgeJob;
    private final List<File> bridgeFiles = new ArrayList<>();
    private int lastTotalFrames = 0;
    private int lastKeptFrames = 0;
    private final Map<String, Integer> variantConflicts = new java.util.HashMap<>();

    private TextView tvVideo, tvStatus, tvResult, tvPhase, tvEndpoint;
    private ProgressBar progress;
    private boolean dispatchPrompted;
    private String chatJob;
    private String lastChatSignature;
    private String chatPollingJob;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                videoUri = uri;
                resetHistorySelection();
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
        tvPhase = findViewById(R.id.tvPhase);
        tvEndpoint = findViewById(R.id.tvEndpoint);
        progress = findViewById(R.id.progress);

        findViewById(R.id.btnPick).setOnClickListener(v -> picker.launch(new String[]{"video/*"}));
        findViewById(R.id.btnRun).setOnClickListener(v -> run());
        findViewById(R.id.btnSolve).setOnClickListener(v -> solve());
        findViewById(R.id.btnPdf).setOnClickListener(v -> makePdf());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnDispatch).setOnClickListener(v -> dispatchPending());
        findViewById(R.id.btnTasks).setOnClickListener(v ->
                startActivity(new Intent(this, TaskActivity.class)));
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistory());
        findViewById(R.id.btnOpenChat).setOnClickListener(v -> openChatActivity());

        String dbg = getIntent().getStringExtra("video");
        if (dbg != null) {
            videoUri = Uri.fromFile(new File(dbg));
            tvVideo.setText("已选择：" + dbg);
            findViewById(R.id.btnRun).setEnabled(true);
            if (getIntent().hasExtra("autorun")) {
                new Handler(Looper.getMainLooper()).postDelayed(this::run, 600);
            }
        }

        if (getIntent().hasExtra("renderTest")) {
            String sample = "1. 样本数据 $6,8,4,5,12$ 的中位数为（ ）\n"
                    + "已知 $A=\\{\\sin\\frac{7\\pi}{6},\\cos\\frac{5\\pi}{3}\\}$，则 $A\\cap B=$\n"
                    + "(2) 设 $O$ 为坐标原点，过 $F$ 的动直线 $l$ 与 $C$ 交于 $P,Q$ 两点，求 $\\tan\\angle PQR$。";
            try {
                android.graphics.Bitmap b = MathTextRenderer.render(this, sample, 1000, 38f,
                        android.graphics.Color.BLACK);
                java.io.File out = new java.io.File(getFilesDir(), "rendertest.png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                }
                setContentView(new android.widget.TextView(this));
                status("已生成 " + out.getName() + " 尺寸 " + b.getWidth() + "x" + b.getHeight());
            } catch (Throwable t) {
                status("渲染测试失败：" + t);
            }
            return;
        }

        // 从任务队列点「打开结果」进来：直接定位到那个任务
        String openJob = getIntent() == null ? null : getIntent().getStringExtra("openJob");
        if (openJob != null && !openJob.isEmpty()) {
            prepareHistoryView(openJob, prefs.bridgeSessionTitle());
            new Thread(() -> downloadJobResults(openJob), "open-job").start();
            return;
        }

        if (prefs.bridgeMode()) {
            showEndpoint();
            String saved = prefs.bridgeJobId();
            if (!saved.isEmpty()) {
                // 上次退出时还有没跑完的任务：自动接上，不用用户再点一次
                activeBridgeJob = saved;
                phase("恢复上次任务");
                tvVideo.setText("电脑上的任务 " + saved.substring(0, 8) + " 还在处理，正在同步状态");
                status("正在恢复电脑上的任务…");
                setBusy(true);
                new Thread(() -> {
                    try {
                        waitForBridgeJob(saved);
                    } catch (Throwable e) {
                        status("恢复失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    } finally {
                        setBusy(false);
                    }
                }, "bridge-resume").start();
            } else {
                phase("等待选择视频");
                status("整段视频一次性上传到电脑，不分片。");
            }
        } else {
            showEndpoint();
            if (prefs.key().isEmpty()) phase("先在设置里填 API Key");
        }
    }

    /** 顶栏副标题：显示当前要连的电脑地址，默认占位地址会标出来。 */
    private void showEndpoint() {
        if (tvEndpoint == null) return;
        String url = prefs.bridgeUrl() == null ? "" : prefs.bridgeUrl().trim();
        if (url.isEmpty()) {
            tvEndpoint.setText("未配置电脑地址 · 点右侧「设置」");
            return;
        }
        boolean placeholder = url.contains("192.168.1.2");
        tvEndpoint.setText(placeholder ? "地址还是默认的 " + url + " · 点「设置」改成电脑 IP" : "电脑 · " + url);
        tvEndpoint.setTextColor(getColorCompat(placeholder ? R.color.warning : R.color.text_tertiary));
    }

    private int getColorCompat(int resId) {
        return android.os.Build.VERSION.SDK_INT >= 23 ? getColor(resId) : 0xFF5B6472;
    }

    /** 状态点跟着阶段变色：一眼看出现在是什么状态。 */
    private void dotFor(String s) {
        int color = 0xFF2F6BFF;
        if (s != null) {
            if (s.contains("完成") || s.contains("恢复")) color = 0xFF1E9E63;
            else if (s.contains("中断") || s.contains("失败") || s.contains("错误")) color = 0xFFC5372C;
            else if (s.contains("等待") || s.contains("确认")) color = 0xFFC77700;
        }
        final int tint = color;
        ui.post(() -> {
            View dot = findViewById(R.id.dotPhase);
            if (dot != null) dot.setBackgroundColor(tint);
        });
    }

    /** 状态卡标题：一句话说清现在处于哪一步。 */
    private void phase(String s) {
        ui.post(() -> {
            if (tvPhase != null) tvPhase.setText(s);
        });
    }

    private void status(String s) {
        ui.post(() -> tvStatus.setText(redact(s)));
    }

    /** 配对令牌一律去掉首尾空白：从 txt 里复制很容易带上换行，塞进 HTTP 头会直接抛异常。 */
    private String bridgeTokenValue() {
        String t = prefs.bridgeToken();
        return t == null ? "" : t.trim();
    }

    /** 回显给用户的文字里不能带令牌原文。 */
    private String redact(String text) {
        if (text == null) return null;
        String t = bridgeTokenValue();
        return t.isEmpty() ? text : text.replace(t, "***");
    }

    private void setBusy(boolean b) {
        busy = b;
        ui.post(() -> {
            progress.setVisibility(b ? View.VISIBLE : View.GONE);
            findViewById(R.id.btnRun).setEnabled(!b && videoUri != null);
            boolean bridge = prefs.bridgeMode();
            // 电脑模式下识别与解题都在电脑侧完成，手机上的这两个按钮没有意义
            View solveBtn = findViewById(R.id.btnSolve);
            solveBtn.setVisibility(bridge ? View.GONE : View.VISIBLE);
            solveBtn.setEnabled(!b && !questions.isEmpty() && !prefs.pageMode());
            boolean hasOut = bridge ? !bridgeFiles.isEmpty()
                    : (prefs.pageMode() ? !pages.isEmpty() : !questions.isEmpty());
            View pdfBtn = findViewById(R.id.btnPdf);
            pdfBtn.setVisibility(bridge && !hasOut ? View.GONE : View.VISIBLE);
            pdfBtn.setEnabled(!b && hasOut);
        });
    }

    // ---------- 局域网桥接：整文件单次上传，轮询 Harness 回传 ----------

    private void runBridgeMode() {
        if (prefs.bridgeUrl().isEmpty() || bridgeTokenValue().isEmpty()) {
            Toast.makeText(this, "请先配置电脑地址和配对令牌", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        bridgeCancelled = false;
        dispatchPrompted = false;
        resetHistorySelection();
        setBusy(true);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        phase("准备上传…");
        tvResult.setText("正在把整段视频一次性上传到电脑，不分片。上传完成后电脑会处理，结果自动下载回来。");
        new Thread(() -> {
            try {
                String savedJob = prefs.bridgeJobId();
                if (!savedJob.isEmpty()) {
                    activeBridgeJob = savedJob;
                    ui.post(() -> tvResult.setText("正在恢复电脑上的任务，视频不用重新上传。"));
                    phase("恢复上次任务");
                    waitForBridgeJob(savedJob);
                    return;
                }
                String name = getDisplayName(videoUri);
                long length;
                try (android.content.res.AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(videoUri, "r")) {
                    length = afd == null ? -1 : afd.getLength();
                }
                File staged = null;
                if (length <= 0) {
                    phase("读取文件信息…");
                    status("这个视频拿不到长度，先在本机复制一份再整传。");
                    staged = stageVideoForLength(name);
                    length = staged.length();
                }
                JSONObject accepted;
                try { accepted = bridgeUpload(name, length, staged); }
                finally { if (staged != null) staged.delete(); }
                String id = accepted.getString("id");
                activeBridgeJob = id;
                prefs.setBridgeJobId(id);
                ui.post(() -> {
                    progress.setProgress(100);
                    tvResult.setText("视频已完整上传到电脑。\n\n任务号：" + id + "\n\n现在等电脑处理，完成后会自动下载结果，不用你操作。");
                });
                phase("已上传 · 等待电脑处理");
                status("整段视频已送达，等待 Harness 读取");
                waitForBridgeJob(id);
            } catch (Throwable e) {
                String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                String hint = raw.contains("192.168.1.2")
                        ? "\n\n电脑地址还是模板里的 192.168.1.2，点右上角「设置」改成电脑的局域网 IP（启动桥接时终端会打印）。"
                        : (raw.contains("timed out") || raw.contains("ETIMEDOUT") || raw.contains("failed to connect")
                        ? "\n\n连不上电脑：确认桥接终端还开着、手机和电脑在同一个 Wi-Fi、Windows 防火墙放行了 8765 端口。"
                        : "");
                phase("上传失败");
                status("上传失败 · 详见下方");
                ui.post(() -> tvResult.setText(redact("上传失败：" + raw + hint)));
            } finally {
                ui.post(() -> {
                    progress.setIndeterminate(false);
                    if (!busy) progress.setVisibility(View.GONE);
                });
                setBusy(false);
            }
        }, "videoquiz-bridge").start();
    }

    private void waitForBridgeJob(String id) throws Exception {
        int polls = 0;
        long started = System.currentTimeMillis();
        while (!bridgeCancelled) {
            Thread.sleep(2500);
            JSONObject state = bridgeRequest("GET", "/api/jobs/" + id + "/status", null, null, 0);
            String phase = state.optString("status", "uploaded");
            String message = state.optString("message", "等待电脑处理");
            polls++;
            long seconds = (System.currentTimeMillis() - started) / 1000;
            String waited = seconds < 60 ? seconds + " 秒" : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
            switch (phase) {
                case "complete":
                    phase("处理完成");
                    break;
                case "processing":
                    phase("电脑正在处理");
                    break;
                case "forwarded":
                    phase("已交给电脑处理");
                    break;
                case "notification_failed":
                    phase("已送达，但通知会话失败");
                    break;
                default:
                    phase("排队等待处理");
            }
            status(message + " · 已等待 " + waited);
            if ("notification_failed".equals(phase)) {
                String retry = "电脑收到了视频，但没能通知处理会话：" + state.optString("notificationError", "未知错误");
                ui.post(() -> tvResult.setText(retry + "\n\n视频已保存在电脑上。检查电脑端桥接服务后可以重试通知。\n\n任务号：" + id));
            }
            if ("stalled".equals(phase)) {
                final String stuckId = id;
                ui.post(() -> {
                    phase("上一次解析已中断");
                    status("这个任务在电脑上停住了（没有产出结果），可以重新派发或忽略");
                    progress.setProgress(0);
                    setBusy(false);
                    com.google.android.material.button.MaterialButton retry = findViewById(R.id.btnDispatch);
                    retry.setText("重新派发这个任务");
                    retry.setVisibility(View.VISIBLE);
                });
                prefs.setBridgeJobId("");
                return;
            }
            final boolean waitingDispatch = "uploaded".equals(phase);
            ui.post(() -> {
                com.google.android.material.button.MaterialButton dispatchBtn = findViewById(R.id.btnDispatch);
                if (waitingDispatch && !prefs.bridgeAutoDispatch()) {
                    dispatchBtn.setText("开始处理（已上传，等你确认）");
                    dispatchBtn.setVisibility(View.VISIBLE);
                    if (!dispatchPrompted) {
                        dispatchPrompted = true;
                        phase("已上传 · 等待确认");
                        status("视频已在电脑上，点「开始处理」才发给会话");
                    }
                } else {
                    dispatchBtn.setVisibility(View.GONE);
                }
            });
            if (!"complete".equals(phase)) continue;
            JSONObject result = bridgeRequest("GET", "/api/jobs/" + id + "/result", null, null, 0);
            String text = result.optString("text", "");
            JSONArray files = result.optJSONArray("files");
            bridgeFiles.clear();
            int totalFiles = files == null ? 0 : files.length();
            if (files != null) {
                for (int i = 0; i < totalFiles; i++) {
                    JSONObject f = files.getJSONObject(i);
                    String name = f.getString("name");
                    long expected = f.optLong("size", 0);
                    File out = new File(getExternalFilesDir(null), id + "_" + name);
                    if (alreadyDownloaded(out, expected)) {
                        status("已有本地副本：" + name + "（" + (i + 1) + "/" + totalFiles + "）");
                    } else {
                        final int seq = i + 1;
                        final int all = totalFiles;
                        final String fileName = name;
                        bridgeDownload(id, name, out, (done, size) -> {
                            if (size > 0) {
                                int pct = (int) (done * 100 / size);
                                status("下载 " + seq + "/" + all + " · " + fileName + " " + pct + "%（"
                                        + formatBytes(done) + " / " + formatBytes(size) + "）");
                            } else {
                                status("下载 " + seq + "/" + all + " · " + fileName + " " + formatBytes(done));
                            }
                        });
                    }
                    bridgeFiles.add(out);
                    if (f.optString("mimeType").equals("application/pdf") && lastPdf == null) lastPdf = out;
                }
            }
            activeBridgeJob = null;
            prefs.setBridgeJobId("");
            String delivered = text.isEmpty() ? "处理完成，结果文件已下载。" : text;
            ui.post(() -> {
                tvResult.setText(delivered);
                View pdfBtn = findViewById(R.id.btnPdf);
                pdfBtn.setVisibility(bridgeFiles.isEmpty() ? View.GONE : View.VISIBLE);
                pdfBtn.setEnabled(!bridgeFiles.isEmpty());
                progress.setProgress(100);
            });
            status("结果已保存到手机，可点下方「打开结果 PDF」查看");
            showChatFor(id);
            return;
        }
        if (bridgeCancelled) {
            phase("已停止等待");
            status("电脑上的任务不受影响，重新点「上传到电脑」可继续查询");
        }
    }

    private File stageVideoForLength(String name) throws Exception {
        File staged = new File(getCacheDir(), "video-upload-" + System.currentTimeMillis() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        try (InputStream in = getContentResolver().openInputStream(videoUri);
             OutputStream out = new java.io.FileOutputStream(staged)) {
            if (in == null) throw new IllegalStateException("无法打开所选视频");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (bridgeCancelled) throw new InterruptedException("upload cancelled");
                out.write(buffer, 0, n);
            }
        } catch (Throwable t) {
            staged.delete();
            throw t;
        }
        return staged;
    }

    private JSONObject bridgeUpload(String name, long length, File staged) throws Exception {
        URL url = new URL(prefs.bridgeUrl() + "/api/jobs");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(0);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(length);
        c.setRequestProperty("Authorization", "Bearer " + bridgeTokenValue());
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Video-Name-B64", android.util.Base64.encodeToString(
                name.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
        if (!prefs.bridgeAutoDispatch()) c.setRequestProperty("X-VideoQuiz-No-Dispatch", "1");
        String targetSession = prefs.bridgeSessionId();
        if (prefs.bridgeNewSession()) {
            try {
                String stamp = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date());
                JSONObject body = new JSONObject();
                body.put("title", "试卷 " + stamp);
                JSONObject created = bridgeRequest("POST", "/api/sessions/create", body.toString(), "application/json", 20000);
                String fresh = created.optString("sessionId", "");
                if (!fresh.isEmpty()) {
                    targetSession = fresh;
                    phase("已新建会话");
                    status("新建会话：" + created.optString("title", fresh));
                }
            } catch (Throwable ignored) {
                // 建会话失败就退回默认目标，不阻塞上传
            }
        }
        if (!targetSession.isEmpty()) {
            c.setRequestProperty("X-VideoQuiz-Session-B64", android.util.Base64.encodeToString(
                    targetSession.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
        }        c.setRequestProperty("X-VideoQuiz-Task-B64", android.util.Base64.encodeToString(
                "请读取视频内容并完成其中的试题，严格保留题号、原题文字及公式；先给出清晰的逐题结果和答案，标注不确定项，并生成可下载的 PDF 与 TXT 结果文件。"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
        try (InputStream in = staged == null ? getContentResolver().openInputStream(videoUri) : new java.io.FileInputStream(staged);
             OutputStream out = c.getOutputStream()) {
            if (in == null) throw new IllegalStateException("无法打开所选视频");
            byte[] buffer = new byte[256 * 1024];
            long sent = 0;
            int read;
            int lastShown = -1;
            long startAt = System.currentTimeMillis();
            while ((read = in.read(buffer)) != -1) {
                if (bridgeCancelled) throw new InterruptedException("upload cancelled");
                out.write(buffer, 0, read);
                sent += read;
                int percent = (int) (sent * 100L / length);
                if (percent != lastShown) {
                    lastShown = percent;
                    final int pct = percent;
                    final long done = sent;
                    final long total = length;
                    long elapsedMs = Math.max(1, System.currentTimeMillis() - startAt);
                    long speed = done * 1000L / elapsedMs;
                    long remainMs = speed > 0 ? (total - done) * 1000L / speed : 0;
                    ui.post(() -> {
                        progress.setProgress(pct);
                        if (pct > 0 && pct < 100) phase("上传中 " + pct + "%");
                    });
                    status(formatBytes(done) + " / " + formatBytes(total)
                            + " · " + formatBytes(speed) + "/s"
                            + (pct >= 100 ? " · 等待电脑确认" : " · 约剩 " + Math.max(1, remainMs / 1000) + " 秒"));
                }
            }
            if (sent != length) throw new IllegalStateException("文件读取长度变化：" + sent + " / " + length);
        }
        int code = c.getResponseCode();
        String body = readHttpBody(c, code);
        c.disconnect();
        if (code != 201) throw new IllegalStateException("电脑返回 " + code + "：" + body);
        return new JSONObject(body);
    }

    private JSONObject bridgeRequest(String method, String path, String body, String contentType, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.bridgeUrl() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeout == 0 ? 30000 : timeout);
        c.setRequestProperty("Authorization", "Bearer " + bridgeTokenValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType == null ? "application/json" : contentType);
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String response = readHttpBody(c, code);
        c.disconnect();
        if (code != 200) throw new IllegalStateException("电脑返回 " + code + "：" + response);
        return new JSONObject(response);
    }

    private String readHttpBody(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) return "";
        try (InputStream in = stream; java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private interface DownloadProgress {
        void onProgress(long done, long total);
    }

    private void bridgeDownload(String id, String name, File target) throws Exception {
        bridgeDownload(id, name, target, null);
    }

    private void bridgeDownload(String id, String name, File target, DownloadProgress progress) throws Exception {
        String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.bridgeUrl() + "/api/jobs/" + id + "/files/" + encoded).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + bridgeTokenValue());
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("下载回传文件失败：HTTP " + code);
        long total = c.getContentLengthLong();
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(target.getPath() + ".part");
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally { c.disconnect(); }
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法覆盖旧文件");
        if (!tmp.renameTo(target)) throw new IllegalStateException("无法写入 " + target.getName());
    }

    /** 本地已有且大小一致，就不重复下载。 */
    private boolean alreadyDownloaded(File target, long expectedSize) {
        return target != null && target.isFile() && (expectedSize <= 0 || target.length() == expectedSize);
    }

    private String getDisplayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Throwable ignored) {}
        return name == null || name.isEmpty() ? "video.mp4" : name;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---------- 识别：抽帧 → 逐帧送模型 ----------

    private void run() {
        if (busy || videoUri == null) return;
        if (prefs.bridgeMode()) {
            runBridgeMode();
            return;
        }
        if (prefs.key().isEmpty()) {
            Toast.makeText(this, "先填 API Key", Toast.LENGTH_SHORT).show();
            showSettings();
            return;
        }
        setBusy(true);
        questions.clear();
        passages.clear();
        pages.clear();
        if (prefs.pageMode()) { runPageMode(); return; }
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

    /** 整页模式：每帧整页转录 → 去重 → 出 PDF */
    private void runPageMode() {
        new Thread(() -> {
            VisionClient client = new VisionClient(prefs.base(), prefs.key(), prefs.model());
            try {
                final List<String> collected = new ArrayList<>();
                final java.util.Set<String> seenLines = new java.util.HashSet<>();
                int kept = FrameSampler.sample(this, videoUri, 1, 40, (bmp, t, sharp, idx, total) -> {
                    try {
                        String page = PageTranscriber.transcribe(client, bmp);
                        if (page != null && page.trim().length() > 60) {
                            String p = page.trim();
                            // 跨页去重：已经出现过的行不再重复输出
                            StringBuilder keptText = new StringBuilder();
                            int fresh = 0;
                            for (String ln : p.split("\n")) {
                                String k = normStem(ln);
                                if (k.length() > 8) {
                                    if (seenLines.contains(k)) continue;
                                    seenLines.add(k);
                                    fresh++;
                                }
                                keptText.append(ln).append('\n');
                            }
                            if (fresh < 4) { bmp.recycle(); return true; }
                            p = keptText.toString().trim();
                            // 同一页会拍到多次：相似就保留更完整的那份
                            // 同一屏会拍到多次：行重叠 ≥ 80% 视为同一页
                            int dupAt = -1;
                            java.util.Set<String> newLines = new java.util.HashSet<>();
                            for (String ln : p.split("\n")) {
                                String k = normStem(ln);
                                if (k.length() > 10) newLines.add(k);
                            }
                            for (int i = 0; i < collected.size(); i++) {
                                if (jaccard(normStem(collected.get(i)), normStem(p)) >= 0.75) {
                                    dupAt = i; break;
                                }
                                java.util.Set<String> oldLines = new java.util.HashSet<>();
                                for (String ln : collected.get(i).split("\n")) {
                                    String k = normStem(ln);
                                    if (k.length() > 10) oldLines.add(k);
                                }
                                if (!newLines.isEmpty()) {
                                    int hit = 0;
                                    for (String k : newLines) if (oldLines.contains(k)) hit++;
                                    if (hit >= newLines.size() * 0.8) { dupAt = i; break; }
                                }
                            }
                            if (dupAt >= 0) {
                                if (p.length() > collected.get(dupAt).length()) collected.set(dupAt, p);
                            } else {
                                collected.add(p);
                            }
                        }
                        final int n = collected.size();
                        ui.post(() -> {
                            progress.setProgress(Math.min(95, idx * 95 / Math.max(1, total)));
                            tvStatus.setText("已读 " + idx + " 帧 · 识别到 " + n + " 页");
                        });
                    } catch (Throwable e) {
                        android.util.Log.w("VideoQuiz", "page " + idx + " failed: " + e.getMessage());
                    } finally {
                        bmp.recycle();
                    }
                    return true;
                }, (d, t) -> {});

                ui.post(() -> {
                    pages.clear();
                    pages.addAll(collected);
                    try {
                        StringBuilder dump = new StringBuilder();
                        for (int i = 0; i < pages.size(); i++) {
                            dump.append("=== 第 ").append(i + 1).append(" 页 (").append(pages.get(i).length()).append(" 字) ===\n")
                                .append(pages.get(i)).append("\n\n");
                        }
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(
                                new java.io.File(getFilesDir(), "pages.txt"));
                        fos.write(dump.toString().getBytes("UTF-8"));
                        fos.close();
                    } catch (Throwable ignored) {}
                    progress.setProgress(100);
                    if (pages.isEmpty()) {
                        tvResult.setText("没有识别到内容。视频可能太糊或画面里没有文字。");
                        status("识别失败");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < pages.size(); i++) {
                            sb.append("—— 第 ").append(i + 1).append(" 页 ——\n")
                              .append(pages.get(i)).append("\n\n");
                        }
                        tvResult.setText(sb.toString());
                        status("整页模式：识别到 " + pages.size() + " 页（共读 " + kept + " 帧）");
                    }
                    findViewById(R.id.btnSolve).setEnabled(false);
                    findViewById(R.id.btnPdf).setEnabled(!pages.isEmpty());
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
        if (prefs.bridgeMode() && !bridgeFiles.isEmpty()) {
            File pdf = lastPdf;
            if (pdf == null) {
                for (File file : bridgeFiles) {
                    if (file.getName().toLowerCase().endsWith(".pdf")) { pdf = file; break; }
                }
            }
            if (pdf != null && pdf.isFile()) {
                openPdf(pdf);
                return;
            }
            ArrayList<Uri> streams = new ArrayList<>();
            for (File file : bridgeFiles) streams.add(FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file));
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("application/octet-stream");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "打开或分享 Harness 回传文件"));
            return;
        }
        if (prefs.pageMode()) {
            if (pages.isEmpty()) return;
            setBusy(true);
            status("正在生成整页 PDF…");
            new Thread(() -> {
                try {
                    File f = PdfExporter.exportPages(getApplicationContext(), getFilesDir(), pages, confidenceReport());
                    ui.post(() -> {
                        status("已生成：" + f.getName());
                        setBusy(false);
                        openPdf(f);
                    });
                } catch (Throwable e) {
                    status("生成失败：" + e.getMessage());
                    setBusy(false);
                }
            }).start();
            return;
        }
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
    /** 内置阅读器优先；打不开再退回系统应用。 */
    private void openPdf(File f) {
        try {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra(PdfViewerActivity.EXTRA_PATH, f.getAbsolutePath());
            startActivity(intent);
        } catch (Throwable e) {
            sharePdf(f);
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
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * dp), pad, 0);

        // ── 模式切换：电脑 / 云端，只显示对应那一组字段 ──
        com.google.android.material.button.MaterialButtonToggleGroup modes =
                new com.google.android.material.button.MaterialButtonToggleGroup(this);
        modes.setSingleSelection(true);
        modes.setSelectionRequired(true);
        modes.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        com.google.android.material.button.MaterialButton bPc =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        bPc.setId(View.generateViewId());
        bPc.setText("电脑 Harness");
        bPc.setTextSize(13);
        com.google.android.material.button.MaterialButton bCloud =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        bCloud.setId(View.generateViewId());
        bCloud.setText("云端 API");
        bCloud.setTextSize(13);
        modes.addView(bPc, new com.google.android.material.button.MaterialButtonToggleGroup.LayoutParams(0, -2, 1f));
        modes.addView(bCloud, new com.google.android.material.button.MaterialButtonToggleGroup.LayoutParams(0, -2, 1f));
        box.addView(modes);
        modes.check(prefs.bridgeMode() ? bPc.getId() : bCloud.getId());

        // ── 电脑模式 ──
        LinearLayout pcBox = new LinearLayout(this);
        pcBox.setOrientation(LinearLayout.VERTICAL);
        EditText eBridge = field(pcBox, "电脑地址", "如 http://10.0.0.5:8765 或 http://[IPv6]:8765", prefs.bridgeUrl());
        EditText eToken = field(pcBox, "配对令牌", "电脑端 bridge/data/token.txt 里的那串字符", prefs.bridgeToken());
        TextView tvTarget = new TextView(this);
        tvTarget.setTextSize(12);
        tvTarget.setTextColor(getColorCompat(R.color.text_secondary));
        tvTarget.setPadding(0, (int) (12 * dp), 0, 0);
        tvTarget.setText(targetLabel());
        com.google.android.material.button.MaterialButton btnTarget =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnTarget.setText("选择目标会话");
        btnTarget.setTextSize(13);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = (int) (8 * dp);
        pcBox.addView(tvTarget, tLp);
        pcBox.addView(btnTarget, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnTarget.setOnClickListener(v -> chooseSession(tvTarget));
        TextView tvTest = new TextView(this);
        tvTest.setTextSize(12);
        tvTest.setTextColor(getColorCompat(R.color.text_secondary));
        tvTest.setPadding(0, (int) (10 * dp), 0, 0);
        com.google.android.material.button.MaterialButton btnTest =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnTest.setText("测试连接");
        btnTest.setTextSize(13);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testLp.topMargin = (int) (10 * dp);
        android.widget.CheckBox cbAuto = new android.widget.CheckBox(this);
        cbAuto.setText("上传后立即派发任务（关掉则手动点「开始处理」）");
        cbAuto.setChecked(prefs.bridgeAutoDispatch());
        cbAuto.setTextSize(12);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = (int) (8 * dp);
        pcBox.addView(cbAuto, cbLp);
        com.google.android.material.button.MaterialButton btnHelp =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnHelp.setText("使用说明");
        btnHelp.setTextSize(13);
        btnHelp.setAllCaps(false);
        btnHelp.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = (int) (10 * dp);
        pcBox.addView(btnHelp, helpLp);
        pcBox.addView(btnTest, testLp);
        pcBox.addView(tvTest);
        box.addView(pcBox);

        // ── 云端模式 ──
        LinearLayout cloudBox = new LinearLayout(this);
        cloudBox.setOrientation(LinearLayout.VERTICAL);
        EditText eBase = field(cloudBox, "接口地址", "如 https://api.deepseek.com", prefs.base());
        EditText eKey = field(cloudBox, "API Key", "服务商控制台里的密钥", prefs.key());
        EditText eModel = field(cloudBox, "模型名", "如 deepseek-chat", prefs.model());
        box.addView(cloudBox);

        android.widget.CheckBox cbPage = new android.widget.CheckBox(this);
        cbPage.setText("整页模式（数学/公式卷：整页转录为 LaTeX，不自动解题）");
        cbPage.setChecked(prefs.pageMode());
        cbPage.setTextSize(12);
        box.addView(cbPage);

        pcBox.setVisibility(prefs.bridgeMode() ? View.VISIBLE : View.GONE);
        cloudBox.setVisibility(prefs.bridgeMode() ? View.GONE : View.VISIBLE);
        modes.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean pc = checkedId == bPc.getId();
            pcBox.setVisibility(pc ? View.VISIBLE : View.GONE);
            cloudBox.setVisibility(pc ? View.GONE : View.VISIBLE);
        });

        btnTest.setOnClickListener(v -> testBridge(eBridge.getText().toString(), eToken.getText().toString(), tvTest));

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(box);
        new MaterialAlertDialogBuilder(this)
                .setTitle("接口设置")
                .setView(settingsScroll)
                .setPositiveButton("保存", (d, w) -> {
                    boolean pc = modes.getCheckedButtonId() == bPc.getId();
                    prefs.save(eBase.getText().toString(), eKey.getText().toString(), eModel.getText().toString());
                    prefs.saveBridge(pc, eBridge.getText().toString(), eToken.getText().toString());
                    prefs.setPageMode(cbPage.isChecked());
                    prefs.setBridgeAutoDispatch(cbAuto.isChecked());
                    showEndpoint();
                    phase(pc ? "等待选择视频" : "云端模式");
                    status(pc ? "已保存：电脑 Harness 模式" : "已保存：云端 " + prefs.model());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 换了新视频或开始新任务时，清掉上一次的历史选中，避免状态串台。 */
    private void resetHistorySelection() {
        chatJob = null;
        chatPollingJob = null;
        lastChatSignature = null;
        ui.post(() -> {
            View button = findViewById(R.id.btnOpenChat);
            if (button != null) button.setVisibility(View.GONE);
            View pdfBtn = findViewById(R.id.btnPdf);
            if (pdfBtn != null) pdfBtn.setVisibility(View.GONE);
            bridgeFiles.clear();
            lastPdf = null;
        });
    }
    /** 打开独立追问页：输入行固定在底部，不会被键盘遮住。 */
    private void openChatActivity() {
        String jobId = chatJob != null && !chatJob.isEmpty() ? chatJob : prefs.bridgeJobId();
        if (jobId == null || jobId.isEmpty()) {
            status("还没有可追问的任务");
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_JOB_ID, jobId);
        String who = prefs.bridgeSessionTitle();
        intent.putExtra(ChatActivity.EXTRA_TITLE, who == null ? "" : who);
        startActivity(intent);
    }

    /** 有结果时显示「继续追问」入口。 */
    private void showChatFor(String jobId) {
        chatJob = jobId;
        View button = findViewById(R.id.btnOpenChat);
        if (button != null) button.setVisibility(View.VISIBLE);
    }
    /** 历史任务：从电脑拉最近的任务，可重新下载结果。 */
    private void showHistory() {
        status("正在读取历史任务…");
        new Thread(() -> {
            try {
                JSONObject payload = bridgeRequest("GET", "/api/jobs", null, null, 20000);
                JSONArray arr = payload.optJSONArray("jobs");
                final ArrayList<String> ids = new ArrayList<>();
                final ArrayList<String> labels = new ArrayList<>();
                final ArrayList<Boolean> doneFlags = new ArrayList<>();
                final ArrayList<String> sessionTitles = new ArrayList<>();
                if (arr != null) {
                    for (int i = arr.length() - 1; i >= 0 && labels.size() < 30; i--) {
                        JSONObject o = arr.getJSONObject(i);
                        String id = o.optString("id", "");
                        if (id.isEmpty()) continue;
                        String state = o.optString("status", "?");
                        String name = o.optString("originalName", "video");
                        long size = o.optLong("size", 0);
                        String when = o.optString("createdAt", "");
                        if (when.length() >= 16) when = when.substring(5, 16).replace('T', ' ');
                        String who = o.optString("targetSessionTitle", "");
                        ids.add(id);
                        sessionTitles.add(who);
                        labels.add(when + " · " + name + " · " + (size / 1024 / 1024) + "MB · " + state
                                + (who.isEmpty() ? "" : " · " + who));
                        doneFlags.add("complete".equals(state));
                    }
                }
                if (labels.isEmpty()) {
                    ui.post(() -> status("电脑上还没有任务"));
                    return;
                }
                final String[] items = labels.toArray(new String[0]);
                ui.post(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("历史任务（点已完成的可重新下载）")
                        .setItems(items, (d, which) -> {
                            if (!doneFlags.get(which)) {
                                status("这条任务状态是 " + labels.get(which) + "，没有结果可下载");
                                return;
                            }
                            prepareHistoryView(ids.get(which), sessionTitles.get(which));
                            downloadJobResults(ids.get(which));
                        })
                        .show());
            } catch (Throwable e) {
                final String message = "读取失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> status(message));
            }
        }, "history").start();
    }

    /** 选中历史后立刻把界面切过去：会话名、追问面板、PDF 按钮占位，都不等下载。 */
    private void prepareHistoryView(String jobId, String sessionTitle) {
        chatJob = jobId;
        String who = sessionTitle == null || sessionTitle.isEmpty() ? "（未知会话）" : sessionTitle;
        ui.post(() -> {
            phase("历史结果");
            tvVideo.setText("任务 " + jobId.substring(0, 8) + " · 会话：" + who);
            status("正在下载结果文件…");
            com.google.android.material.button.MaterialButton button = findViewById(R.id.btnOpenChat);
            if (button != null) {
                button.setVisibility(View.VISIBLE);
                button.setText("继续追问 · 会话：" + who);
            }
            prefs.saveBridgeSession(prefs.bridgeSessionId(), who);
            tvResult.setText("正在从电脑取回这次任务的结果…");
            View pdfBtn = findViewById(R.id.btnPdf);
            if (pdfBtn != null) {
                pdfBtn.setVisibility(View.VISIBLE);
                pdfBtn.setEnabled(false);
            }
        });
        chatJob = jobId;
    }
    /** 把某个已完成任务的结果文件重新下载到手机并展示文本。 */
    private void downloadJobResults(String id) {
        status("正在下载 " + id.substring(0, 8) + " 的结果…");
        new Thread(() -> {
            try {
                JSONObject result = bridgeRequest("GET", "/api/jobs/" + id + "/result", null, null, 30000);
                String text = result.optString("text", "");
                JSONArray files = result.optJSONArray("files");
                bridgeFiles.clear();
                int reused = 0;
                int total = files == null ? 0 : files.length();
                if (files != null) {
                    for (int i = 0; i < total; i++) {
                        JSONObject f = files.getJSONObject(i);
                        String name = f.getString("name");
                        long expected = f.optLong("size", 0);
                        File out = new File(getExternalFilesDir(null), id + "_" + name);
                        if (alreadyDownloaded(out, expected)) {
                            reused++;
                            status("已有本地副本：" + name + "（" + (i + 1) + "/" + total + "）");
                        } else {
                            final int seq = i + 1;
                            final int all = total;
                            final String fileName = name;
                            bridgeDownload(id, name, out, (done, size) -> {
                                if (size > 0) {
                                    int pct = (int) (done * 100 / size);
                                    status("下载 " + seq + "/" + all + " · " + fileName + " " + pct + "%（"
                                            + formatBytes(done) + " / " + formatBytes(size) + "）");
                                } else {
                                    status("下载 " + seq + "/" + all + " · " + fileName + " " + formatBytes(done));
                                }
                            });
                        }
                        bridgeFiles.add(out);
                        if (f.optString("mimeType").equals("application/pdf") && lastPdf == null) lastPdf = out;
                    }
                }
                final int reuseCount = reused;
                final String delivered = text.isEmpty() ? (reuseCount > 0 ? "结果已在本机，未重复下载。" : "结果已下载。") : text;
                ui.post(() -> {
                    tvResult.setText(delivered);
                    View pdfBtn = findViewById(R.id.btnPdf);
                    pdfBtn.setVisibility(bridgeFiles.isEmpty() ? View.GONE : View.VISIBLE);
                    pdfBtn.setEnabled(!bridgeFiles.isEmpty());
                    phase("已恢复历史结果");
                    status(reuseCount > 0 ? "结果已在本机（复用 " + reuseCount + " 个文件），可打开 PDF 或继续追问"
                            : "结果已下载，可打开 PDF 或继续追问");
                });
                showChatFor(id);
                status("已下载 " + bridgeFiles.size() + " 个文件");
            } catch (Throwable e) {
                final String message = "下载失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> status(message));
            }
        }, "history-download").start();
    }
    /** 手动派发：把已上传但还没投递的任务交给目标会话。 */
    private void dispatchPending() {
        String id = activeBridgeJob != null ? activeBridgeJob : prefs.bridgeJobId();
        if (id == null || id.isEmpty()) {
            status("没有待派发的任务");
            return;
        }
        final String jobId = id;
        findViewById(R.id.btnDispatch).setVisibility(View.GONE);
        status("正在派发…");
        new Thread(() -> {
            try {
                String path = "/api/jobs/" + jobId + "/forward?force=1";
                JSONObject result = bridgeRequest("POST", path, "{}", "application/json", 20000);
                boolean ok = result.optBoolean("ok", false);
                ui.post(() -> {
                    if (ok) {
                        phase("已派发 · 等待电脑处理");
                        status("任务已交给目标会话");
                    } else {
                        status("派发失败：" + result.optString("error", result.optString("message", "未知错误")));
                        findViewById(R.id.btnDispatch).setVisibility(View.VISIBLE);
                    }
                });
            } catch (Throwable e) {
                final String message = "派发失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> {
                    status(message);
                    findViewById(R.id.btnDispatch).setVisibility(View.VISIBLE);
                });
            }
        }, "dispatch").start();
    }
    /** 弹窗让用户自己起名，然后在电脑工作区里建一个会话并选中它。 */
    private void promptNewSession(TextView out) {
        EditText input = new EditText(this);
        input.setHint("会话名称，例如：期末数学");
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("新建会话")
                .setMessage("会话会建在电脑上的「视频成卷」工作区里。")
                .setView(holder)
                .setPositiveButton("创建", (d, w) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) {
                        status("名字不能为空");
                        return;
                    }
                    createNamedSession(out, title);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createNamedSession(TextView out, String title) {
        out.setText("正在创建会话「" + title + "」…");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("title", title);
                JSONObject created = bridgeRequest("POST", "/api/sessions/create", body.toString(), "application/json", 20000);
                String sid = created.optString("sessionId", "");
                if (sid.isEmpty()) throw new IllegalStateException("电脑没有返回会话 ID");
                String got = created.optString("title", title);
                ui.post(() -> {
                    prefs.saveBridgeSession(sid, got);
                    prefs.setBridgeNewSession(false);
                    out.setText(targetLabel());
                    status("已新建并选中：" + got);
                });
            } catch (Throwable e) {
                final String message = "新建失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> out.setText(message));
            }
        }, "new-session").start();
    }
    /** 设置里显示当前选的投递目标。 */
    private String targetLabel() {
        if (prefs.bridgeNewSession()) return "目标会话：每次上传新建";
        String title = prefs.bridgeSessionTitle();
        return title.isEmpty() ? "目标会话：电脑默认" : "目标会话：" + title;
    }

    /** 从电脑拉会话列表，选一个作为上传后的投递目标。 */
    private void chooseSession(TextView out) {
        out.setText("正在读取电脑上的会话…");
        new Thread(() -> {
            try {
                JSONObject payload = bridgeRequest("GET", "/api/sessions", null, null, 15000);
                JSONArray arr = payload.optJSONArray("sessions");
                final ArrayList<String> ids = new ArrayList<>();
                final ArrayList<String> labels = new ArrayList<>();
                ids.add("");
                labels.add("＋ 新建会话（自己命名）");
                ids.add("");
                labels.add("＋ 每次上传新建会话");
                ids.add("");
                labels.add("不指定（用电脑默认会话）");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String sid = o.optString("sessionId", "");
                        if (sid.isEmpty()) continue;
                        String title = o.optString("title", sid);
                        ids.add(sid);
                        labels.add(title + (o.optBoolean("running", false) ? " · 运行中" : ""));
                    }
                }
                final String[] items = labels.toArray(new String[0]);
                ui.post(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("投递到哪个会话")
                        .setItems(items, (d, which) -> {
                            if (which == 0) {
                                promptNewSession(out);
                                return;
                            }
                            if (which == 1) {
                                prefs.saveBridgeSession("", "");
                                prefs.setBridgeNewSession(true);
                                out.setText(targetLabel());
                                status("已选择：每次上传新建会话");
                                return;
                            }
                            String sid = ids.get(which);
                            String title = which == 2 ? "" : labels.get(which);
                            prefs.saveBridgeSession(sid, title);
                            prefs.setBridgeNewSession(false);
                            out.setText(targetLabel());
                            status("已选择：" + (title.isEmpty() ? "电脑默认" : title));
                        })
                        .show());
            } catch (Throwable e) {
                final String message = "读取会话失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> out.setText(message));
            }
        }, "session-picker").start();
    }
    /** 在设置里当场验证电脑桥接：先探活，再用令牌验一次鉴权，把可能的坑说清楚。 */
    private void testBridge(String rawUrl, String rawToken, TextView out) {
        String url = rawUrl == null ? "" : rawUrl.trim().replaceAll("/+$", "");
        String token = rawToken == null ? "" : rawToken.trim();
        if (url.isEmpty()) {
            out.setText("先填电脑地址，桥接启动时终端会打印。");
            return;
        }
        out.setTextColor(getColorCompat(R.color.text_secondary));
        out.setText("正在连接 " + url + " …");
        new Thread(() -> {
            String message;
            int color;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url + "/api/health").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                int code = c.getResponseCode();
                if (code == 200) {
                    if (token.isEmpty()) {
                        message = "电脑在线。还没填配对令牌。";
                        color = R.color.warning;
                    } else {
                        HttpURLConnection a = (HttpURLConnection) new URL(url + "/api/jobs").openConnection();
                        a.setConnectTimeout(6000);
                        a.setReadTimeout(6000);
                        a.setRequestProperty("Authorization", "Bearer " + token);
                        int ac = a.getResponseCode();
                        if (ac == 200) {
                            message = "连接成功，令牌有效，可以上传。";
                            color = R.color.success;
                        } else if (ac == 401) {
                            message = "电脑在线，但令牌不对。从电脑的 bridge/data/token.txt 重新复制。";
                            color = R.color.danger;
                        } else {
                            message = "电脑在线，但校验返回 " + ac + "。";
                            color = R.color.warning;
                        }
                    }
                } else {
                    message = "电脑返回 " + code + "，不是预期的桥接服务。";
                    color = R.color.warning;
                }
            } catch (Throwable e) {
                String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                String hint;
                if (raw.contains("UnknownHost") || raw.contains("Unable to resolve host")) {
                    hint = "地址可能写错了，或手机解析不了这个主机名。";
                } else if (raw.contains("failed to connect") || raw.contains("ConnectException")) {
                    hint = "连不上这台电脑。依次确认：① 电脑上桥接终端还开着；② Windows 防火墙放行了 8765；③ 手机和电脑在同一 Wi-Fi，且这个 Wi-Fi 没开设备隔离（校园网常见），必要时用手机热点或 Tailscale。";
                } else if (raw.contains("timed out") || raw.contains("timeout")) {
                    hint = "连接超时，多半被防火墙或网络隔离挡住，不是令牌问题。";
                } else {
                    hint = "看看地址是否带 http://、端口是否是 8765。";
                }
                message = "连不上：" + raw + "\n\n" + hint;
                color = R.color.danger;
            }
            final String m = message;
            final int col = color;
            ui.post(() -> {
                out.setText(redact(m));
                out.setTextColor(getColorCompat(col));
                // 结果那一行可能被滚出视野（设置项变多了），Toast 兜个底
                String brief = redact(m).split("\n")[0];
                if (brief.length() > 60) brief = brief.substring(0, 60) + "…";
                Toast.makeText(this, brief, Toast.LENGTH_LONG).show();
            });
        }, "bridge-test").start();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        return field(parent, null, hint, value);
    }

    /** 带标题的输入框：标题在上、占位提示在框里，避免用户不知道这一行填什么。 */
    private EditText field(LinearLayout parent, String label, String hint, String value) {
        float dp = getResources().getDisplayMetrics().density;

        if (label != null && !label.isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(label);
            caption.setTextSize(12);
            caption.setTextColor(getColorCompat(R.color.text_secondary));
            LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            capLp.topMargin = (int) (14 * dp);
            parent.addView(caption, capLp);
        }

        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) ((label == null || label.isEmpty() ? 8 : 4) * dp);
        parent.addView(e, lp);
        return e;
    }
}
