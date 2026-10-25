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

    public void save(String base, String key, String model) {
        sp.edit().putString("base", base.trim()).putString("key", key.trim())
                .putString("model", model.trim()).apply();
    }
}