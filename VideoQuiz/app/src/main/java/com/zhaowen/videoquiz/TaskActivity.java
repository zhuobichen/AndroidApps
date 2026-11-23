package com.zhaowen.videoquiz;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 任务队列：一次看全部任务，可派发 / 取消 / 重派 / 看结果 / 追问。 */
public class TaskActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout queueList;
    private TextView tvHint;
    private String lastSignature;
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tasks);
        queueList = findViewById(R.id.queueList);
        tvHint = findViewById(R.id.tvQueueHint);
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            lastSignature = null;
            refresh();
        });
        refresh();
        pollLoop();
    }

    private void pollLoop() {
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3000);
                    refresh();
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }, "queue-poll").start();
    }

    private void refresh() {
        new Thread(() -> {
            try {
                JSONObject payload = request("GET", "/api/jobs", null);
                JSONArray jobs = payload.optJSONArray("jobs");
                if (jobs == null) jobs = new JSONArray();

                StringBuilder signature = new StringBuilder();
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject j = jobs.optJSONObject(i);
                    if (j == null) continue;
                    signature.append(j.optString("id", "")).append(j.optString("status", "")).append(';');
                }
                final String sig = signature.toString();
                if (sig.equals(lastSignature)) return;
                lastSignature = sig;

                final JSONArray list = jobs;
                ui.post(() -> render(list));
            } catch (Throwable e) {
                final String message = "读取失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> tvHint.setText(message));
            }
        }, "queue-refresh").start();
    }

    private void render(JSONArray jobs) {
        queueList.removeAllViews();

        int active = 0;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null) continue;
            String status = job.optString("status", "?");
            if ("processing".equals(status) || "forwarded".equals(status) || "forwarding".equals(status)) active++;
        }
        tvHint.setText("共 " + jobs.length() + " 个任务，其中 " + active + " 个在处理中");

        // 最新的排在最上面
        for (int i = jobs.length() - 1; i >= 0; i--) {
            JSONObject job = jobs.optJSONObject(i);
            if (job != null) queueList.addView(buildRow(job));
        }
    }

    private View buildRow(JSONObject job) {
        String id = job.optString("id", "");
        String status = job.optString("status", "?");
        String name = job.optString("originalName", "video");
        long size = job.optLong("size", 0);
        String created = job.optString("createdAt", "");
        String sessionTitle = job.optString("targetSessionTitle", "");
        boolean finished = "complete".equals(status);

        // 整行 = 勾选框 + 内容列；靠发丝线分隔，不用卡片
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        row.setLayoutParams(rowLp);

        // 状态标记：完成 = 蓝底白勾；其余 = 空心圆
        View mark = new View(this);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        markLp.topMargin = dp(1);
        markLp.setMarginEnd(dp(14));
        mark.setLayoutParams(markLp);
        mark.setBackgroundResource(finished
                ? com.zhaowen.videoquiz.R.drawable.bg_checkbox_on
                : com.zhaowen.videoquiz.R.drawable.bg_checkbox_off);
        row.addView(mark);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(col);

        // 主行：状态 + 短号
        TextView head = new TextView(this);
        head.setText(statusLabel(status) + "   " + id.substring(0, 8));
        head.setTextSize(15);
        head.setTextColor(finished ? 0xFF1A1A1A : statusColor(status));
        col.addView(head);

        // 次行：时间 · 文件名 · 大小 · 会话
        TextView meta = new TextView(this);
        StringBuilder metaText = new StringBuilder();
        metaText.append(formatTime(created)).append("  ·  ").append(name).append("  ·  ")
                .append(size / 1024 / 1024).append("MB");
        if (!sessionTitle.isEmpty()) metaText.append("  ·  ").append(sessionTitle);
        meta.setText(metaText.toString());
        meta.setTextSize(12.5f);
        meta.setTextColor(0xFF6E6E73);
        meta.setPadding(0, dp(3), 0, 0);
        col.addView(meta);

        // 操作：文字型蓝链接，不抢视觉
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if ("uploaded".equals(status)) {
            actions.addView(actionButton("开始处理", () -> act(id, "forward", "已派发")));
        }
        if ("stalled".equals(status)) {
            actions.addView(actionButton("重新派发", () -> act(id, "resume-then-forward", "已重新派发")));
        }
        if ("processing".equals(status) || "forwarded".equals(status) || "forwarding".equals(status)) {
            actions.addView(actionButton("取消", () -> act(id, "cancel", "已取消")));
        }
        if ("cancelled".equals(status)) {
            actions.addView(actionButton("恢复", () -> act(id, "resume", "已恢复")));
        }
        if ("complete".equals(status)) {
            actions.addView(actionButton("打开结果", () -> openResult(id)));
            actions.addView(actionButton("追问", () -> openChat(id, sessionTitle)));
        }
        if (actions.getChildCount() > 0) {
            actions.setPadding(0, dp(2), 0, 0);
            col.addView(actions);
        }

        outer.addView(row);

        // 缩进发丝线
        View line = new View(this);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.topMargin = dp(14);
        lineLp.setMarginStart(dp(36));
        line.setLayoutParams(lineLp);
        line.setBackgroundColor(0xFFECECEF);
        outer.addView(line);

        return outer;
    }

    private View actionButton(String label, Runnable onClick) {
        TextView link = new TextView(this);
        link.setText(label);
        link.setTextSize(13.5f);
        link.setTextColor(0xFF0A84FF);
        link.setPadding(0, dp(6), dp(18), dp(6));
        link.setOnClickListener(v -> onClick.run());
        return link;
    }

    private void act(String id, String action, String okText) {
        new Thread(() -> {
            try {
                if ("forward".equals(action)) {
                    request("POST", "/api/jobs/" + id + "/forward", "{}");
                } else if ("resume-then-forward".equals(action)) {
                    try {
                        request("POST", "/api/jobs/" + id + "/resume", "{}");
                    } catch (Throwable ignored) {
                    }
                    // force=1：上次已投递过但没产出，桥接否则会幂等跳过
                    request("POST", "/api/jobs/" + id + "/forward?force=1", "{}");
                } else {
                    request("POST", "/api/jobs/" + id + "/" + action, "{}");
                }
                ui.post(() -> {
                    Toast.makeText(this, okText, Toast.LENGTH_SHORT).show();
                    lastSignature = null;
                    refresh();
                });
            } catch (Throwable e) {
                final String message = "操作失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }
        }, "queue-act").start();
    }

    private void openResult(String id) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("openJob", id);
        startActivity(intent);
        finish();
    }

    private void openChat(String id, String sessionTitle) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_JOB_ID, id);
        intent.putExtra(ChatActivity.EXTRA_TITLE, sessionTitle == null ? "" : sessionTitle);
        startActivity(intent);
    }

    private static String statusLabel(String status) {
        switch (status) {
            case "complete": return "已完成";
            case "processing": return "处理中";
            case "forwarded": return "已派发";
            case "forwarding": return "派发中";
            case "stalled": return "已中断";
            case "cancelled": return "已取消";
            case "uploaded": return "待处理";
            default: return status;
        }
    }

    private static int statusColor(String status) {
        switch (status) {
            case "complete": return 0xFF2E9E63;
            case "stalled": return 0xFFD9534F;
            case "cancelled": return 0xFF9AA1AC;
            case "uploaded": return 0xFF2F6BFF;
            default: return 0xFFB8860B;
        }
    }

    private static String formatTime(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(iso);
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            return out.format(date);
        } catch (Throwable e) {
            return iso.length() >= 16 ? iso.substring(5, 16).replace('T', ' ') : iso;
        }
    }

    private JSONObject request(String method, String path, String payload) throws Exception {
        Prefs prefs = new Prefs(this);
        String base = prefs.bridgeUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + prefs.bridgeToken().trim());
        if (payload != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(payload.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = stream == null ? "" : new String(readAll(stream), "UTF-8");
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + body);
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
