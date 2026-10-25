package com.zhaowen.videoquiz;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 调用支持视觉的接口，把一帧画面读成题目 JSON。 */
public class VisionClient {

    public static class Result {
        public List<Question> questions = new ArrayList<>();
        public String passage = "";
    }

    private final String base, key, model;

    public VisionClient(String base, String key, String model) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.model = model;
    }

    private static final String PROMPT =
            "这是一页试卷的照片。你的任务只有一个：把图中出现的所有【题目】完整抄出来。\n" +
            "输出 JSON：{\"questions\":[{\"no\":题号,\"stem\":\"题干\",\"options\":[\"A. ...\",\"B. ...\",\"C. ...\",\"D. ...\"]}],\"passage\":\"\"}\n" +
            "规则：\n" +
            "1. questions 是重点，务必把本页每道题的题干和全部选项都抄全；\n" +
            "2. no 填数字题号（如 24）；\n" +
            "3. passage 只有在【本页没有题目、只有文章正文】时才填正文，否则一律空字符串；\n" +
            "4. 只输出 JSON，不要解释；本页没有题目就返回 {\"questions\":[],\"passage\":\"该页正文\"}。";

    public Result readFrame(Bitmap bmp) throws Exception {
        // 缩到最长边 1400，压成 JPEG，控制 token
        Bitmap scaled = scaleDown(bmp, 1400);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        if (scaled != bmp) scaled.recycle();
        String b64 = Base64.getEncoder().encodeToString(bos.toByteArray());

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", PROMPT));
        content.put(new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));

        JSONArray msgs = new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", content));

        JSONObject req = new JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("temperature", 0)
                .put("max_tokens", 2000);

        String reply = post("/chat/completions", req.toString());
        JSONObject resp = new JSONObject(reply);
        String text = resp.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");

        Result r = new Result();
        JSONObject obj = extractObject(text);
        android.util.Log.i("VideoQuiz", "replyLen=" + text.length() + " objNull=" + (obj == null));
        if (obj == null) return r;
        r.passage = obj.optString("passage", "");
        JSONArray arr = obj.optJSONArray("questions");
        android.util.Log.i("VideoQuiz", "arrNull=" + (arr == null) + " arrLen=" + (arr == null ? -1 : arr.length()));
        if (arr == null) return r;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Question q = new Question();
            q.no = toInt(o.opt("no"));
            q.stem = o.optString("stem", "").trim();
            JSONArray ops = o.optJSONArray("options");
            if (ops != null) {
                for (int j = 0; j < ops.length(); j++) {
                    String s = ops.optString(j, "").trim();
                    if (!s.isEmpty()) q.options.add(s);
                }
            }
            if (q.no > 0 && !q.stem.isEmpty()) r.questions.add(q);
        }
        return r;
    }

    /** 纯文本调用（解题用） */
    public String chat(String system, String user) throws Exception {
        JSONArray msgs = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject req = new JSONObject()
                .put("model", model).put("messages", msgs)
                .put("temperature", 0).put("max_tokens", 3000);
        String reply = post("/chat/completions", req.toString());
        return new JSONObject(reply).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
    }

    private String post(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
        }
        if (code != 200) throw new Exception("HTTP " + code + "：" + cut(sb.toString()));
        return sb.toString();
    }

    /** 模型可能把题号写成 "24" 字符串，统一成整数 */
    public static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String cut(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static JSONObject extractObject(String text) {
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(text.substring(a, b + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return src;
        float k = maxSide / (float) m;
        return Bitmap.createScaledBitmap(src, Math.round(w * k), Math.round(h * k), true);
    }
}