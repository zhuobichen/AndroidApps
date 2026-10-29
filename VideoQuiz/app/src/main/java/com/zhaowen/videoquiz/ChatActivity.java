package com.zhaowen.videoquiz;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 独立的追问页：列表占满剩余空间，输入行固定在底部。
 * 这样键盘弹起时只压缩列表，输入行始终浮在键盘上方。
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "jobId";
    public static final String EXTRA_TITLE = "sessionTitle";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private EditText chatInput;
    private TextView tvHeader;

    private String jobId;
    private String sessionTitle;
    private String lastSignature;
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        jobId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_JOB_ID);
        sessionTitle = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TITLE);

        chatList = findViewById(R.id.chatList);
        chatScroll = findViewById(R.id.chatScroll);
        chatInput = findViewById(R.id.chatInput);
        tvHeader = findViewById(R.id.tvChatHeader);

        String who = TextUtils.isEmpty(sessionTitle) ? "（未知会话）" : sessionTitle;
        tvHeader.setText("继续追问 · 会话：" + who + "（只追加消息，不重新生成 PDF）");

        findViewById(R.id.chatSend).setOnClickListener(v -> send());
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            send();
            return true;
        });

        if (TextUtils.isEmpty(jobId)) {
            Toast.makeText(this, "缺少任务信息", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pollLoop();
    }

    private void pollLoop() {
        new Thread(() -> {
            while (running) {
                try {
                    JSONObject payload = request("GET", "/api/jobs/" + jobId + "/chat?limit=40", null);
                    JSONArray messages = payload.optJSONArray("messages");
                    final String title = payload.optString("title", "");
                    ui.post(() -> {
                        if (!TextUtils.isEmpty(title) && tvHeader != null) {
                            tvHeader.setText("继续追问 · 会话：" + title + "（只追加消息，不重新生成 PDF）");
                        }
                    });
                    render(messages);
                } catch (Throwable ignored) {
                    // 网络抖动：下一轮再试
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }, "chat-poll").start();
    }

    private void render(JSONArray messages) {
        if (messages == null) return;
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            signature.append(m.optString("id", "")).append('|').append(m.optString("text", "").length()).append(';');
        }
        final String sig = signature.toString();
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;

        ui.post(() -> {
            if (chatList == null) return;
            chatList.removeAllViews();
            int start = Math.max(0, messages.length() - 40);
            for (int i = start; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "?");
                String text = m.optString("text", "");
                if (text.trim().isEmpty()) continue;

                boolean mine = "user".equals(role);
                TextView bubble = new TextView(this);
                bubble.setText(text);
                bubble.setTextSize(13.5f);
                bubble.setTextColor(0xFF14181F);
                bubble.setPadding(dp(12), dp(9), dp(12), dp(9));

                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setCornerRadius(dp(14));
                shape.setColor(mine ? 0xFFE4ECFF : 0xFFF3F5F9);
                bubble.setBackground(shape);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                bubble.setLayoutParams(lp);
                bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82));
                chatList.addView(bubble);
            }
            chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void send() {
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInput.setText("");
        Toast.makeText(this, "已发送，等待回复…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text);
                JSONObject result = request("POST", "/api/jobs/" + jobId + "/chat", body.toString());
                if (!result.optBoolean("ok", false)) {
                    final String message = "发送失败：" + result.optString("message", result.optString("code", "未知"));
                    ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                }
            } catch (Throwable e) {
                final String message = "发送失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }
        }, "chat-send").start();
    }

    /** 直连桥接，复用 MainActivity 的地址与令牌设置。 */
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
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
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
