package com.zhaowen.videoquiz;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** 启动封面：显示作者与版本，短暂停留后进入主界面。 */
public class SplashActivity extends AppCompatActivity {

    private static final long HOLD_MS = 900;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean moved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        TextView version = findViewById(R.id.tvVersion);
        if (version != null) {
            try {
                String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                if (name != null && !name.isEmpty()) version.setText("v" + name);
            } catch (Throwable ignored) {
                // 取不到版本就保留默认文本
            }
        }

        ui.postDelayed(this::goMain, HOLD_MS);
    }

    private void goMain() {
        if (moved) return;
        moved = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
