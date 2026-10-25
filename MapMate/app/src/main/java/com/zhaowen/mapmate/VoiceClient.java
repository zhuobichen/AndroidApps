package com.zhaowen.mapmate;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** 小智协议客户端：hello / listen / tts / iot + 二进制 Opus 帧。 */
public class VoiceClient {

    private static final String TAG = "MapMateWS";

    public interface Listener {
        void onStatus(String s);
        void onAssistantText(String text);
        void onUserText(String text);
        void onIotCommand(String method, String place);
    }

    private static final String DEVICE_ID = "aa:bb:cc:dd:ee:01";

    private final String url;
    private final Listener listener;
    private final AudioIO audio;
    private final OkHttpClient client;
    private volatile WebSocket ws;
    private volatile boolean open;
    private volatile boolean closed = false;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    public VoiceClient(String url, AudioIO audio, Listener listener) {
        this.url = url;
        this.audio = audio;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    public boolean isOpen() { return open; }

    public void connect() {
        Request req = new Request.Builder()
                .url(url)
                .addHeader("device-id", DEVICE_ID)
                .addHeader("client-id", DEVICE_ID)
                .addHeader("protocol-version", "1")
                .build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                open = true;
                Log.i(TAG, "ws open");
                sendHello();
                sendDescriptors();
                listener.onStatus("已连接");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleJson(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                audio.playOpus(bytes.toByteArray());
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                open = false;
                Log.e(TAG, "ws failure", t);
                listener.onStatus("连接失败，3 秒后重试: " + t.getMessage());
                scheduleReconnect();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                open = false;
                Log.i(TAG, "ws closed: " + reason);
                listener.onStatus("连接已断开，3 秒后重试");
                scheduleReconnect();
            }
        });
    }

    private void sendHello() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "hello");
            o.put("version", 1);
            o.put("transport", "websocket");
            JSONObject ap = new JSONObject();
            ap.put("format", "opus");
            ap.put("sample_rate", AudioIO.REC_RATE);
            ap.put("channels", 1);
            ap.put("frame_duration", AudioIO.FRAME_MS);
            o.put("audio_params", ap);
            ws.send(o.toString());
            Log.i(TAG, "hello sent");
        } catch (Exception e) { Log.e(TAG, "hello failed", e); }
    }

    private void sendDescriptors() {
        try {
            JSONObject prop = new JSONObject();
            prop.put("location", new JSONObject()
                    .put("description", "用户当前所在地点描述")
                    .put("type", "string"));
            prop.put("route", new JSONObject()
                    .put("description", "最近一次规划的路线概要，例如 10.9 公里 · 约 20 分钟")
                    .put("type", "string"));

            JSONObject params = new JSONObject();
            params.put("place", new JSONObject()
                    .put("description", "目的地名称或地址，例如 天安门、广州塔")
                    .put("type", "string"));

            JSONObject methods = new JSONObject();
            methods.put("navigate", new JSONObject()
                    .put("description", "让手机地图导航到指定地点")
                    .put("parameters", params));
            methods.put("stop_navigation", new JSONObject()
                    .put("description", "结束当前导航并清除地图上的路线"));

            JSONObject desc = new JSONObject();
            desc.put("name", "map");
            desc.put("description", "用户的手机地图");
            desc.put("properties", prop);
            desc.put("methods", methods);

            JSONArray arr = new JSONArray();
            arr.put(desc);

            JSONObject msg = new JSONObject();
            msg.put("type", "iot");
            msg.put("descriptors", arr);
            ws.send(msg.toString());
            Log.i(TAG, "iot descriptors sent");
        } catch (Exception e) { Log.e(TAG, "descriptor failed", e); }
    }

    private void handleJson(String text) {
        if (text == null) return;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            Log.w(TAG, "non-json from server: " + trimmed);
            listener.onStatus(trimmed);
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            Log.i(TAG, "recv: " + type + " " + text.substring(0, Math.min(160, text.length())));

            if ("hello".equals(type)) {
                listener.onStatus("服务端已就绪");
            } else if ("tts".equals(type)) {
                String state = o.optString("state");
                if ("sentence_start".equals(state)) {
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) listener.onAssistantText(t);
                }
            } else if ("stt".equals(type)) {
                String t = o.optString("text", "");
                if (!t.isEmpty()) listener.onUserText(t);
            } else if ("iot".equals(type)) {
                JSONArray cmds = o.optJSONArray("commands");
                if (cmds != null) {
                    for (int i = 0; i < cmds.length(); i++) {
                        JSONObject c = cmds.getJSONObject(i);
                        String method = c.optString("method");
                        JSONObject p = c.optJSONObject("parameters");
                        String place = p != null ? p.optString("place", "") : "";
                        Log.i(TAG, "iot command: " + method + " place=" + place);
                        listener.onIotCommand(method, place);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parse failed: " + text, e);
        }
    }

    private void send(JSONObject o) {
        WebSocket w = ws;
        if (w != null) w.send(o.toString());
    }

    public void startListening() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "start");
            o.put("mode", "manual");
            send(o);
            audio.startRecording(this::sendAudioFrame);
        } catch (Exception e) { Log.e(TAG, "start failed", e); }
    }

    public void stopListening() {
        try {
            audio.stopRecording();
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "stop");
            o.put("mode", "manual");
            send(o);
        } catch (Exception e) { Log.e(TAG, "stop failed", e); }
    }

    public void sendText(String text) {
        try {
            audio.flushPlayback();
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "detect");
            o.put("text", text);
            o.put("source", "text");
            send(o);
        } catch (Exception e) { Log.e(TAG, "text failed", e); }
    }

    public void sendIoTState(String location) {
        sendIoTState(location, "");
    }

    public void sendIoTState(String location, String route) {
        try {
            JSONObject st = new JSONObject().put("location", location).put("route", route);
            JSONObject item = new JSONObject().put("name", "map").put("state", st);
            JSONArray arr = new JSONArray().put(item);
            JSONObject msg = new JSONObject().put("type", "iot").put("states", arr);
            send(msg);
        } catch (Exception e) { Log.e(TAG, "state failed", e); }
    }

    private void sendAudioFrame(byte[] opus) {
        WebSocket w = ws;
        if (w != null) w.send(ByteString.of(opus));
    }

    private void scheduleReconnect() {
        if (closed) return;
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(() -> {
            if (!closed && !open) {
                Log.i(TAG, "reconnecting...");
                connect();
            }
        }, 3000);
    }

    public void close() {
        closed = true;
        open = false;
        ui.removeCallbacksAndMessages(null);
        if (ws != null) ws.close(1000, "bye");
        client.dispatcher().executorService().shutdown();
    }
}