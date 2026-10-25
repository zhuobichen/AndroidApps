package com.zhaowen.mapmate;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 全量对话归档：只追加，不删。压缩只影响发给模型的内容，不影响留档。 */
public class HistoryArchive {

    private static final String TAG = "MapMateHistory";
    private static final String FILE = "history.jsonl";
    private static final long MAX_BYTES = 4 * 1024 * 1024;

    public static synchronized void append(Context ctx, String role, String content) {
        if (content == null || content.isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (f.length() > MAX_BYTES) {
                File old = new File(ctx.getFilesDir(), "history.1.jsonl");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            JSONObject o = new JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("role", role)
                    .put("content", content);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "append failed: " + t.getMessage());
        }
    }

    /** 最近 maxLines 条，拼成 JSON 数组字符串 */
    public static String tailJson(Context ctx, int maxLines) {
        List<String> lines = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return "[]";
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                lines.add(line);
                if (lines.size() > maxLines) lines.remove(0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "tail failed: " + t.getMessage());
        }
        return "[" + String.join(",", lines) + "]";
    }

    /** 最近 n 条，格式化成「时间 角色: 内容」 */
    public static String recentFormatted(Context ctx, int maxLines) {
        List<String> lines = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return "还没有对话记录。";
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                lines.add(line);
                if (lines.size() > maxLines) lines.remove(0);
            }
        } catch (Throwable t) {
            return "读取失败：" + t.getMessage();
        }
        if (lines.isEmpty()) return "还没有对话记录。";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            try {
                JSONObject o = new JSONObject(l);
                String role = "user".equals(o.optString("role")) ? "你" : "小德";
                sb.append(fmt.format(new java.util.Date(o.optLong("ts"))))
                  .append("  ").append(role).append("：")
                  .append(o.optString("content")).append("\n\n");
            } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    public static int count(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return 0;
        int n = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) n++;
        } catch (Throwable ignored) {}
        return n;
    }
}