package com.zhaowen.videoquiz;

import android.content.Context;
import android.content.SharedPreferences;

/** 接口配置：默认 DeepSeek，也可填任意 OpenAI 兼容地址。 */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("videoquiz", Context.MODE_PRIVATE);
    }

    public String base() {
        return sp.getString("base", "https://api.deepseek.com");
    }

    public String key() {
        return sp.getString("key", "");
    }

    public String model() {
        return sp.getString("model", "deepseek-chat");
    }

    public boolean pageMode() {
        return sp.getBoolean("pageMode", false);
    }

    public void setPageMode(boolean on) {
        sp.edit().putBoolean("pageMode", on).apply();
    }

    public boolean bridgeMode() {
        return sp.getBoolean("bridgeMode", false);
    }

    public String bridgeUrl() {
        return sp.getString("bridgeUrl", "http://192.168.1.2:8765");
    }

    public String bridgeToken() {
        return sp.getString("bridgeToken", "");
    }

    public void saveBridge(boolean enabled, String url, String token) {
        sp.edit().putBoolean("bridgeMode", enabled)
                .putString("bridgeUrl", url.trim().replaceAll("/+$", ""))
                .putString("bridgeToken", token.trim()).apply();
    }

    public String bridgeJobId() {
        return sp.getString("bridgeJobId", "");
    }

    public void setBridgeJobId(String id) {
        sp.edit().putString("bridgeJobId", id).apply();
    }

    /** 上传后投递到哪个会话；空串表示用电脑端的默认目标。 */
    public String bridgeSessionId() {
        return sp.getString("bridgeSessionId", "");
    }

    public String bridgeSessionTitle() {
        return sp.getString("bridgeSessionTitle", "");
    }

    /** 上传完是否立刻派发任务；关掉后由用户点「开始处理」。 */
    public boolean bridgeAutoDispatch() {
        return sp.getBoolean("bridgeAutoDispatch", true);
    }

    public void setBridgeAutoDispatch(boolean on) {
        sp.edit().putBoolean("bridgeAutoDispatch", on).apply();
    }
    /** 每次上传都新建一个会话（视频试卷这类重任务用）。 */
    public boolean bridgeNewSession() {
        return sp.getBoolean("bridgeNewSession", false);
    }

    public void setBridgeNewSession(boolean on) {
        sp.edit().putBoolean("bridgeNewSession", on).apply();
    }
    public void saveBridgeSession(String id, String title) {
        sp.edit().putString("bridgeSessionId", id == null ? "" : id.trim())
                .putString("bridgeSessionTitle", title == null ? "" : title.trim()).apply();
    }

    public void save(String base, String key, String model) {
        sp.edit().putString("base", base.trim()).putString("key", key.trim())
                .putString("model", model.trim()).apply();
    }
}