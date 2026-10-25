package com.zhaowen.mapmate;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 直连大模型（DeepSeek / OpenAI 兼容），带工具调用与三层记忆。 */
public class Agent {

    private static final String TAG = "MapMateAgent";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface ToolHandler {
        String run(String name, JSONObject args);
    }

    public interface Callback {
        void onReply(String text);
        void onError(String message);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ToolHandler tools;
    private final MemoryStore memory;
    private volatile String locationContext = "";

    private static final String SYSTEM_PROMPT =
            "你是「小德」，用户手机地图里的出行助手。说话简短、口语化，一次回复不超过两句话。\n" +
            "用户说\"去某地/导航到某地/带我去某地\"时，调用 navigate，place 取最完整的地点名。用户说\"先去A再去B\"时，place=B，via=A。用户说\"骑车/骑行/走路/步行\"并要去某地时，仍然必须调用 navigate，把 mode 填成 骑行 或 步行；不要只在对话里说\"已切换\"而不调用工具。即使已经在同一条路线上，用户再次明确要求导航时也要重新调用 navigate，不要跳过。\n" +
            "用户说\"结束导航/取消导航\"时，调用 stop_navigation。\n" +
            "用户说\"记住这里是家/把这里设为公司\"时，调用 set_place。用户明确说\"记住……\"时，调用 save_memory。\n" +
            "地点不明确时先问一句再调工具。不知道就说不知道，绝不编造路线、距离、时间。";

    public Agent(String baseUrl, String apiKey, String model, ToolHandler tools, MemoryStore memory) {
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.tools = tools;
        this.memory = memory;
    }

    /** 由 MainActivity 在定位变化时更新 */
    public void setLocationContext(String s) { this.locationContext = s == null ? "" : s; }

    private static String trimSlash(String s) {
        if (s == null || s.isEmpty()) return "https://api.deepseek.com";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private JSONArray toolSpec() throws Exception {
        JSONObject place = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("place", new JSONObject()
                                .put("type", "string")
                                .put("description", "目的地名称或地址，例如 广州塔、深圳湾公园"))
                        .put("via", new JSONObject()
                                .put("type", "string")
                                .put("description", "可选，途经点名称，多个用英文逗号分隔。用户说先去A再去B时，place=B，via=A"))
                        .put("mode", new JSONObject()
                                .put("type", "string")
                                .put("description", "可选，驾车/骑行/步行，默认驾车")))
                .put("required", new JSONArray().put("place"));

        JSONObject memArg = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("content", new JSONObject()
                        .put("type", "string")
                        .put("description", "要长期记住的一句话，例如 我家在天河区XX小区")))
                .put("required", new JSONArray().put("content"));

        JSONObject placeName = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("name", new JSONObject()
                        .put("type", "string")
                        .put("description", "家 或 公司")))
                .put("required", new JSONArray().put("name"));

        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "navigate").put("description", "让手机地图导航到指定地点").put("parameters", place)));
        arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "stop_navigation").put("description", "结束当前导航并清除地图上的路线")
                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()))));
        arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "get_location")
                .put("description", "获取用户当前所在位置（坐标与地址），回答\"我在哪\"或判断距离时调用")
                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()))));
        arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "set_place").put("description", "把用户当前所在位置记为家或公司")
                .put("parameters", placeName)));
        arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "save_memory").put("description", "把用户明确要求长期记住的信息写入记忆")
                .put("parameters", memArg)));
        return arr;
    }

    public void send(final String userText, final Callback cb) {
        new Thread(() -> {
            try {
                memory.addTurn("user", userText);

                List<JSONObject> convo = new ArrayList<>();
                String sys = SYSTEM_PROMPT + "\n\n" + memory.memoryBlock(userText);
                if (!locationContext.isEmpty()) sys += "\n[当前定位]\n" + locationContext + "\n";
                convo.add(new JSONObject().put("role", "system").put("content", sys));
                for (MemoryStore.Turn t : memory.working()) {
                    convo.add(new JSONObject().put("role", t.role).put("content", t.content));
                }

                for (int round = 0; round < 3; round++) {
                    JSONObject msg = post(convo, true)
                            .getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    JSONArray calls = msg.optJSONArray("tool_calls");

                    if (calls == null || calls.length() == 0) {
                        String text = msg.optString("content", "").trim();
                        memory.addTurn("assistant", text);
                        memory.save();
                        memory.compressIfNeeded(this::llmOnce);
                        memory.extractFactsIfNeeded(this::llmOnce);
                        cb.onReply(text.isEmpty() ? "我在" : text);
                        return;
                    }

                    convo.add(new JSONObject()
                            .put("role", "assistant")
                            .put("content", msg.optString("content", ""))
                            .put("tool_calls", calls));

                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.getJSONObject(i);
                        JSONObject fn = call.getJSONObject("function");
                        String name = fn.optString("name");
                        JSONObject args = new JSONObject(fn.optString("arguments", "{}"));
                        Log.i(TAG, "tool call: " + name + " " + args);
                        String result = tools.run(name, args);
                        convo.add(new JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", call.optString("id"))
                                .put("content", result));
                    }
                }
                cb.onError("模型调用轮次过多");
            } catch (Throwable t) {
                Log.e(TAG, "send failed", t);
                cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, "agent").start();
    }

    /** 记忆模块用的单次调用（不带工具） */
    private String llmOnce(String prompt, int maxTokens) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens)
                .put("messages", msgs);
        return postBody(body)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .optString("content", "");
    }

    private JSONObject post(List<JSONObject> messages, boolean withTools) throws Exception {
        JSONObject placeName = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("name", new JSONObject()
                        .put("type", "string")
                        .put("description", "家 或 公司")))
                .put("required", new JSONArray().put("name"));

        JSONArray arr = new JSONArray();
        for (JSONObject m : messages) arr.put(m);
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.3)
                .put("max_tokens", 400)
                .put("messages", arr);
        if (withTools) {
            JSONArray spec = toolSpec();
            body.put("tools", spec);
            body.put("tool_choice", "auto");
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < spec.length(); i++) {
                if (i > 0) names.append(",");
                names.append(spec.getJSONObject(i).getJSONObject("function").optString("name"));
            }
            Log.i(TAG, "tools: " + names);
        }
        return postBody(body);
    }

    private JSONObject postBody(JSONObject body) throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + ": " + text);
            return new JSONObject(text);
        }
    }
}