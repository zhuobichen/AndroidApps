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
        public List<String> writings = new ArrayList<>();
        public String passage = "";
    }

    private final String base, key, model;

    public VisionClient(String base, String key, String model) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.model = model;
    }

    private static final String PROMPT =
            "这是一页英语试卷的照片。请把图中出现的所有【带题号的内容】提取出来。\n" +
            "输出 JSON：{\"questions\":[{\"no\":题号,\"stem\":\"题干\",\"options\":[\"A. ...\",\"B. ...\"]}],\"passage\":\"\"}\n\n" +
            "本页可能是下面任意一种，都要处理：\n" +
            "（1）普通选择题：题号 + 题干 + A/B/C/D 选项；\n" +
            "（2）完形填空正文：文章里出现 __41__、__42__ 这样的带编号空格——每个空格算一道题，stem 取包含该空格的那一整句；\n" +
            "（3）完形填空选项表：形如「41. A. fantasy  B. prejudice  C. realization  D. expectation」——每一行算一道题，options 填 A-D，stem 留空；\n" +
            "（4）语法填空：正文里带编号的空格，后面括号里给了提示词（如 62___(Draw)）——每个空格算一道题，stem 取包含该空格的句子，options 留空数组。\n\n" +
            "规则：\n" +
            "1. 只要图里有题号就必须提取，不要因为「没有选项」或「是文章」就跳过；\n" +
            "2. no 必须是数字；\n" +
            "3. passage 填本页的【文章正文】（可以是空的）；本页即使也有题目，也要把正文抄进 passage；但不要把题目、选项写进 passage；\n" +
            "3b. passage 必须是【逐字抄录】：不要改写、概括、翻译、补全；看不清的词照抄形状或用 […] 标记；段落之间用 \\n\\n 分隔；\n" +
            "4. 如果本页是【写作题】（应用文/读后续写，没有题号），把题目原文完整放进 writing 数组；\n" +
            "5. 只输出 JSON，不要解释。\n" +
            "完整格式：{\"questions\":[...],\"passage\":\"\",\"writing\":[\"应用文题目原文\",\"读后续写题目原文\"]}";

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
        JSONArray wr = obj.optJSONArray("writing");
        if (wr != null) {
            for (int i = 0; i < wr.length(); i++) {
                String s = wr.optString(i, "").trim();
                if (s.length() > 20) r.writings.add(s);
            }
        }
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
            // 听力原文的对话行（M: / W: 开头）不是题目，丢掉
            String st = q.stem.trim();
            String firstLine = st.split("\\r?\\n")[0].trim();
            // 听力原文的对话行（M: / W: 开头）不是题目，丢掉
            boolean isDialogue = firstLine.length() > 1
                    && (firstLine.charAt(0) == 'M' || firstLine.charAt(0) == 'W')
                    && (firstLine.charAt(1) == ':' || firstLine.charAt(1) == '：');
            if (isDialogue) continue;
            // 完形选项表只有选项没有题干，也要保留
            boolean noOk = q.no >= 1 && q.no <= 200 && !(q.no >= 2000);
            if (noOk && (!q.stem.isEmpty() || q.options.size() >= 2)) r.questions.add(q);
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