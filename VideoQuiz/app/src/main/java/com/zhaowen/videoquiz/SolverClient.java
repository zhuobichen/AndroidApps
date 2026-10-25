package com.zhaowen.videoquiz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 调用 OpenAI 兼容接口解题（默认 DeepSeek）。 */
public class SolverClient {

    private final String base, key, model;

    public SolverClient(String base, String key, String model) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.model = model;
    }

    public String solve(List<Question> qs) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Question q : qs) {
            body.append(q.no).append(". ").append(q.stem).append('\n');
            for (String o : q.options) body.append(o).append('\n');
            body.append('\n');
        }

        String sys = "你是英语老师。根据题目给出答案，只输出 JSON 数组，不要解释格式。";
        String usr = "题目如下：\n\n" + body +
                "\n请输出 JSON 数组，每项 {\"no\":题号,\"answer\":\"选项字母\",\"reason\":\"一句话中文理由\"}。";

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", sys));
        msgs.put(new JSONObject().put("role", "user").put("content", usr));

        JSONObject req = new JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("temperature", 0.0)
                .put("max_tokens", 2048);

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
        }
        if (code != 200) throw new Exception("HTTP " + code + ": " + trim(sb.toString()));

        JSONObject resp = new JSONObject(sb.toString());
        String content = resp.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
        return content;
    }

    /** 从模型回复里提取 JSON 数组（容忍 ```json 包裹和前后废话） */
    public static JSONArray extractJson(String text) throws Exception {
        int a = text.indexOf('[');
        int b = text.lastIndexOf(']');
        if (a < 0 || b <= a) throw new Exception("回复里没有 JSON 数组");
        return new JSONArray(text.substring(a, b + 1));
    }

    private static String trim(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}