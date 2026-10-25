package com.zhaowen.mapmate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import com.google.android.material.button.MaterialButton;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.navi.AmapNaviPage;
import com.amap.api.navi.AmapNaviParams;
import com.amap.api.navi.AmapNaviType;
import com.amap.api.navi.AmapPageType;
import com.amap.api.maps.model.Poi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity implements VoiceClient.Listener {

    private static final String DEFAULT_WS = "ws://10.0.2.2:8000/xiaozhi/v1/";
    private static final String DEFAULT_BASE = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-flash";

    private MapView mapView;
    private AMap aMap;
    private TextView status;
    private TextView routeInfo;
    private Guidance guidance;
    private VoiceClient voice;
    private AudioIO audio;
    private Agent agent;
    private MemoryStore memory;
    private LinearLayout altBar;
    private RoutePlanner.Route currentRoute;
    private int selectedPath = 0;
    private RoutePlanner.Mode currentMode = RoutePlanner.Mode.DRIVE;
    private String plate = "";
    private RideTracker rideTracker;
    private TextView rideHud;
    private SensorManager sensorManager;
    private android.location.LocationListener locListener;
    private boolean locStarted = false;
    private boolean hasFix = false;
    private Marker myMarker;
    private float compassAzimuth = 0;
    private boolean hasCompass = false;
    private LatLng guidePos = null;
    private String currentAddress = "";
    private long lastAddressAt = 0;
    private boolean centeredOnUser = false;
    private long lastImpactAt = 0;
    private long lastMoveAt = 0;
    private boolean fallPrompted = false;
    private String emergency = "";
    private String mode = "direct";
    private String cloudToken = "";
    private String cloudGist = "";
    private String cloudType = "gist";
    private String davUrl = "";
    private String davUser = "";
    private String davPass = "";
    private String davPin = "";
    private double weightKg = 65;
    private int lastKm = 0;
    private String routeSummary = "";
    private String lastGuide = "正在定位…";
    private int routeAscent = -1;
    private TextToSpeech replyTts;
    private TextToSpeech naviTts;
    private SpeechRecognizer speech;
    private SharedPreferences sp;

    private String serverWs = DEFAULT_WS;
    private String apiBase = DEFAULT_BASE;
    private String apiKey = "";
    private String apiModel = DEFAULT_MODEL;

    private LatLng myLocation = new LatLng(23.1570, 113.3442);   // 华南理工大学北一门
    private String currentPlace = "";
    private LatLng currentDest;
    private boolean following = true;
    private LocationManager lm;
    private boolean trafficOn = false;
    private boolean is3d = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        routeInfo = findViewById(R.id.routeInfo);
        altBar = findViewById(R.id.altBar);
        rideHud = findViewById(R.id.rideHud);
        guidance = new Guidance();

        memory = new MemoryStore(this);
        sp = getSharedPreferences("mapmate", MODE_PRIVATE);
        serverWs = sp.getString("ws", DEFAULT_WS);
        apiBase = sp.getString("apiBase", DEFAULT_BASE);
        apiKey = sp.getString("apiKey", "");
        apiModel = sp.getString("apiModel", DEFAULT_MODEL);
        plate = sp.getString("plate", "");
        emergency = sp.getString("emergency", "");
        mode = sp.getString("mode", "direct");
        cloudToken = sp.getString("cloudToken", "");
        cloudGist = sp.getString("cloudGist", "");
        cloudType = sp.getString("cloudType", "gist");
        davUrl = sp.getString("davUrl", "");
        davUser = sp.getString("davUser", "");
        davPass = sp.getString("davPass", "");
        davPin = sp.getString("davPin", "");
        weightKg = sp.getFloat("weight", 65f);
        rideTracker = new RideTracker(this);
        rideTracker.setWeight(sp.getFloat("weight", 65f));
        if (rideTracker.isRiding()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                ((Button) findViewById(R.id.btnRide)).setText("结束");
                updateRideHud();
                setStatus("继续上次的骑行（" + String.format("%.2f", rideTracker.meters() / 1000) + " km）· 点「结束」保存");
            }, 800);
        }

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        aMap = mapView.getMap();
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 16f));
        aMap.setOnMapLoadedListener(() -> {
            View loading = findViewById(R.id.mapLoading);
            if (loading != null) loading.setVisibility(View.GONE);
        });
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            View loading = findViewById(R.id.mapLoading);
            if (loading != null) loading.setVisibility(View.GONE);
        }, 15000);

        int nightMask = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            aMap.setMapType(AMap.MAP_TYPE_NIGHT);
        }
        setupMapUi();

        replyTts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) replyTts.setLanguage(Locale.CHINA);
        });
        naviTts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) naviTts.setLanguage(Locale.CHINA);
        });

        audio = new AudioIO();
        audio.init();

        setupAgentOrServer();

        Button ptt = findViewById(R.id.btnVoice);
        ptt.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startVoiceInput();
                v.setPressed(true);
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                stopVoiceInput();
                v.setPressed(false);
                return true;
            }
            return false;
        });

        EditText input = findViewById(R.id.input);
        findViewById(R.id.btnSend).setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            if (t.startsWith("ws://") || t.startsWith("wss://")) {
                sp.edit().putString("ws", t).apply();
                serverWs = t;
                if (voice != null) voice.close();
                voice = new VoiceClient(t, audio, this);
                voice.connect();
                setStatus("服务器已切换到 " + t);
                input.setText("");
                return;
            }
            handleUserText(t);
            input.setText("");
        });

        routeInfo.setOnLongClickListener(v -> { showRouteDetail(); return true; });
        EditText searchInput = findViewById(R.id.searchInput);
        findViewById(R.id.btnSearch).setOnClickListener(v -> {
            String kw = searchInput.getText().toString().trim();
            if (!kw.isEmpty()) doSearch(kw);
            else showNearbyCategories();
        });

        findViewById(R.id.btnSettings).setOnClickListener(v -> showMainMenu());
        status.setOnClickListener(v -> showSettings());
        findViewById(R.id.btnSettings).setOnLongClickListener(v -> { showMemory(); return true; });

        String searchKw = getIntent() != null ? getIntent().getStringExtra("search") : null;
        if (searchKw != null && !searchKw.isEmpty()) {
            final String kw = searchKw;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> doSearch(kw), 2500);
        }

        String cmd = getIntent() != null ? getIntent().getStringExtra("cmd") : null;
        if (cmd != null && !cmd.isEmpty()) {
            final String q = cmd;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> handleUserText(q), 4000);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setStatus("未授予定位权限 · 点这里开启");
            status.setOnClickListener(v -> openAppSettings());
        }
        requestPerms();
        startLocationUpdates();
    }

    /** 自绘定位箭头：位置来自 GPS，朝向来自罗盘 */
    private void setupMyLocationMarker() {
        try {
            int s = 96;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    s, s, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF1A73E8);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(s / 2f, 5);
            path.lineTo(s * 0.86f, s - 6);
            path.lineTo(s / 2f, s * 0.72f);
            path.lineTo(s * 0.14f, s - 6);
            path.close();
            canvas.drawPath(path, fill);
            android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(7);
            canvas.drawPath(path, stroke);

            myMarker = aMap.addMarker(new MarkerOptions()
                    .position(myLocation)
                    .icon(com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bmp))
                    .anchor(0.5f, 0.5f)
                    .visible(false)
                    .zIndex(20));
        } catch (Throwable t) {
            android.util.Log.w("MapMate", "marker failed: " + t.getMessage());
        }

        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rot != null) sm.registerListener(compassListener, rot, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable ignored) {}
    }

    private final SensorEventListener compassListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            try {
                float[] r = new float[9];
                float[] o = new float[3];
                SensorManager.getRotationMatrixFromVector(r, e.values);
                SensorManager.getOrientation(r, o);
                float az = (float) Math.toDegrees(o[0]);
                if (az < 0) az += 360;
                compassAzimuth = az;
                hasCompass = true;
                if (myMarker != null) myMarker.setRotateAngle(az);
            } catch (Throwable ignored) {}
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void moveMyMarker(LatLng p) {
        if (p == null) return;
        myLocation = p;
        if (myMarker != null) {
            myMarker.setPosition(p);
            if (!myMarker.isVisible()) myMarker.setVisible(true);
        }
        updateLocationContext();
        if (!centeredOnUser && !rideTracker.isRiding() && currentRoute == null) {
            centeredOnUser = true;
            try { aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 16f), 600, null); } catch (Throwable ignored) {}
        }
    }

    /** 把坐标（和缓存的地址）同步给 Agent，并每分钟刷新一次逆地理 */
    private void updateLocationContext() {
        if (agent != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("纬度 ").append(myLocation.latitude).append("，经度 ").append(myLocation.longitude);
            if (currentAddress != null && !currentAddress.isEmpty()) sb.append("，地址：").append(currentAddress);
            agent.setLocationContext(sb.toString());
        }
        long now = System.currentTimeMillis();
        if (now - lastAddressAt < 60000) return;
        lastAddressAt = now;
        Geocoder.reverse(myLocation.latitude, myLocation.longitude, addr -> {
            if (addr != null && !addr.isEmpty()) {
                currentAddress = addr;
                updateLocationContext();
            }
        });
    }

    /** 常驻更新 myLocation：GPS + 网络双源，先用上次已知位置 */
    private void startLocationUpdates() {
        if (locStarted) return;
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                setStatusSticky("未授予定位权限 · 点这里开启");
                status.setOnClickListener(v -> openAppSettings());
                return;
            }
            locStarted = true;
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);

            // 1) 先从所有 provider 里挑最新的一次已知位置，立刻显示
            android.location.Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignored) {}
            }
            if (best != null) {
                moveMyMarker(new LatLng(best.getLatitude(), best.getLongitude()));
                hasFix = true;
                android.util.Log.i("MapMateLoc", "lastKnown " + best.getProvider() + " " + best.getLatitude() + "," + best.getLongitude());
            }

            locListener = new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location loc) {
                    if (loc != null) {
                        moveMyMarker(new LatLng(loc.getLatitude(), loc.getLongitude()));
                        hasFix = true;
                        onRideFix(myLocation, loc.hasSpeed() ? loc.getSpeed() : -1);
                    }
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2000, 0, locListener);
                } catch (Throwable ignored) {}
            }

            // 8 秒还没有定位就提示用户
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!hasFix) setStatusSticky("正在定位… 请到窗边或室外");
            }, 8000);
        } catch (Throwable ignored) {}
    }

    // ---------- 地图展示 ----------

    private void setupMapUi() {
        UiSettings ui = aMap.getUiSettings();
        ui.setZoomControlsEnabled(false);
        ui.setCompassEnabled(true);
        ui.setScaleControlsEnabled(true);
        ui.setTiltGesturesEnabled(true);
        ui.setRotateGesturesEnabled(true);
        ui.setMyLocationButtonEnabled(false);

        try {
            aMap.setMyLocationEnabled(false);
            setupMyLocationMarker();
        } catch (Throwable ignored) {}

        aMap.setOnMapLongClickListener(latLng -> {
            setStatus("正在解析选点…");
            Geocoder.reverse(latLng.latitude, latLng.longitude, addr -> {
                final String label = addr == null || addr.isEmpty() ? "选定位置" : addr;
                aMap.clear();
                aMap.addMarker(new MarkerOptions().position(latLng).title(label));
                setStatus("选中：" + label);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(label)
                        .setItems(new String[]{"导航到这里", "收藏这里"}, (d, w) -> {
                            if (w == 0) {
                                planTo(label, latLng);
                            } else {
                                boolean ok = memory.addFavorite(label, label, latLng.latitude, latLng.longitude);
                                setStatus(ok ? "已收藏：" + label : "这个点已经收藏过");
                            }
                        })
                        .show();
            });
        });

        findViewById(R.id.btnLayer).setOnClickListener(v -> showLayerDialog());
        findViewById(R.id.btnTraffic).setOnClickListener(v -> {
            trafficOn = !trafficOn;
            aMap.setTrafficEnabled(trafficOn);
            ((Button) v).setText(trafficOn ? "路况·开" : "路况");
            setStatus(trafficOn ? "实时路况已开启" : "实时路况已关闭");
        });
        findViewById(R.id.btnCollapse).setOnClickListener(v -> toggleCollapse());
        findViewById(R.id.btnStartNav).setOnClickListener(v -> startNavigation());
        findViewById(R.id.btnEndNav).setOnClickListener(v -> endNavigation());
        findViewById(R.id.btnModeDrive).setOnClickListener(v -> setMode(RoutePlanner.Mode.DRIVE));
        findViewById(R.id.btnModeBike).setOnClickListener(v -> setMode(RoutePlanner.Mode.BIKE));
        findViewById(R.id.btnModeWalk).setOnClickListener(v -> setMode(RoutePlanner.Mode.WALK));
        findViewById(R.id.btnRide).setOnClickListener(v -> toggleRide());
        findViewById(R.id.btnRide).setOnLongClickListener(v -> { showRideMenu(); return true; });
        findViewById(R.id.btnLocate).setOnClickListener(v -> locateMe());
        findViewById(R.id.btnLocate).setOnLongClickListener(v -> { showFavorites(); return true; });
        findViewById(R.id.btn3d).setOnClickListener(v -> {
            is3d = !is3d;
            aMap.animateCamera(CameraUpdateFactory.changeTilt(is3d ? 45f : 0f), 500, null);
            ((Button) v).setText(is3d ? "2D" : "3D");
            setStatus(is3d ? "3D 视角" : "2D 视角");
        });
    }

    private void showLayerDialog() {
        final String[] items = {"标准地图", "卫星地图", "夜间模式"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("地图图层")
                .setItems(items, (d, which) -> {
                    int type = which == 0 ? AMap.MAP_TYPE_NORMAL
                            : which == 1 ? AMap.MAP_TYPE_SATELLITE
                            : AMap.MAP_TYPE_NIGHT;
                    aMap.setMapType(type);
                    setStatus("已切换：" + items[which]);
                })
                .show();
    }

    private void locateMe() {
        if (!hasFix) {
            setStatus("正在定位… 请到窗边或室外");
            startLocationUpdates();
        }
        LatLng target = myLocation;
        try {
            android.location.Location loc = aMap.getMyLocation();
            if (loc != null && loc.getLatitude() != 0) {
                target = new LatLng(loc.getLatitude(), loc.getLongitude());
                myLocation = target;
            }
        } catch (Throwable ignored) {}
        following = true;
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16f), 600, null);
        setStatus("已定位到当前位置（跟随中）");
    }

    // ---------- 模式切换 ----------

    private void setupAgentOrServer() {
        if ("server".equals(mode)) {
            agent = null;
            if (voice == null) {
                voice = new VoiceClient(serverWs, audio, this);
                voice.connect();
            }
            setStatus("服务端模式 · " + serverWs);
            return;
        }
        if (voice != null) { voice.close(); voice = null; }
        if (!apiKey.isEmpty()) {
            agent = new Agent(apiBase, apiKey, apiModel, this::runTool, memory);
            updateLocationContext();
            setStatus("直连 " + apiModel + " · 右上角「菜单」可改");
        } else {
            agent = null;
            setStatus("未配置：右上角「菜单」→「设置」填入 Key");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::maybeOnboard, 1500);
        }
    }

    private String runTool(String name, JSONObject args) {
        if ("navigate".equals(name)) {
            String place = args.optString("place", "");
            String via = args.optString("via", "");
            String mode = args.optString("mode", "");
            runOnUiThread(() -> doNavigate(place, via, mode));
            return "已开始导航到" + place + (via.isEmpty() ? "" : "，途经" + via);
        }
        if ("get_location".equals(name)) {
            StringBuilder sb = new StringBuilder();
            sb.append("纬度 ").append(myLocation.latitude)
              .append("，经度 ").append(myLocation.longitude);
            if (currentAddress != null && !currentAddress.isEmpty()) sb.append("，地址：").append(currentAddress);
            return sb.toString();
        }
        if ("stop_navigation".equals(name)) {
            runOnUiThread(this::doStopNavigation);
            return "已结束导航";
        }
        if ("set_place".equals(name)) {
            String nm = args.optString("name", "");
            final String key = (nm.contains("公司") || nm.contains("单位")) ? "company" : "home";
            final String label = "company".equals(key) ? "公司" : "家";
            final LatLng cur = myLocation;
            Geocoder.reverse(cur.latitude, cur.longitude, addr -> {
                String a = (addr == null || addr.isEmpty()) ? label : addr;
                memory.setPlace(key, a, cur.latitude, cur.longitude);
                memory.replaceFactsAbout(label, "home".equals(key) ? "我家在" + a : "我公司在" + a);
                setStatus("已把当前位置记为" + label + "：" + a);
            });
            return "已把当前位置记为" + label;
        }
        if ("save_memory".equals(name)) {
            String content = args.optString("content", "").trim();
            if (content.isEmpty()) return "没有可记住的内容";
            boolean added = memory.addFact(content);
            return added ? "已记住：" + content : "这条已经记过了";
        }
        return "未知工具: " + name;
    }

    private long lastPlanAt = 0;
    private String lastPlanKey = "";

    /** 统一规划入口：1.5 秒内同一目的地不重复请求 */
    private void planTo(String name, LatLng dest) {
        long now = System.currentTimeMillis();
        String key = name + "@" + dest.latitude + "," + dest.longitude;
        if (key.equals(lastPlanKey) && now - lastPlanAt < 1500) return;
        lastPlanKey = key;
        lastPlanAt = now;
        setStatus("正在规划…");
        RoutePlanner.plan(myLocation, dest, currentMode, null, plate, r -> drawRoute(name, dest, r));
    }

    /** 骑行场景菜单：补给点 / 记录 */
    /** 顶部「菜单」：把隐藏功能都放到台面上 */
    private void showMainMenu() {
        final String[] items = {"设置", "对话记录", "收藏", "骑行记录", "小德记得的事", "离线地图"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("MapMate")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showSettings(); break;
                        case 1: showHistory(); break;
                        case 2: showFavorites(); break;
                        case 3: showRideHistory(); break;
                        case 4: showMemory(); break;
                        default: openOfflineMap(); break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 对话记录：可回看最近 200 条 */
    private void showHistory() {
        String text = HistoryArchive.recentFormatted(this, 200);
        android.util.Log.i("MapMateHistory", "len=" + text.length());
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(text == null || text.isEmpty() ? "还没有对话记录。" : text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF1F1F1F);
        tv.setTextIsSelectable(true);
        tv.setPadding((int) (20 * d), (int) (14 * d), (int) (20 * d), (int) (14 * d));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, (int) (380 * d)));
        sv.addView(tv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.addView(sv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("对话记录")
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showRideMenu() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("骑行")
                .setItems(new String[]{"找补给点", "骑行记录", "当前天气", "离线地图"}, (d, w) -> {
                    if (w == 0) showSupplyCategories();
                    else if (w == 1) showRideHistory();
                    else if (w == 2) speakWeather();
                    else openOfflineMap();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openOfflineMap() {
        try {
            startActivity(new Intent(this, com.amap.api.maps.offlinemap.OfflineMapActivity.class));
            setStatus("离线地图：按城市下载");
        } catch (Throwable t) {
            setStatus("打不开离线地图：" + t.getMessage());
        }
    }

    private void showSupplyCategories() {
        final String[] items = {"便利店", "公厕", "自行车店", "药店", "快餐"};
        new MaterialAlertDialogBuilder(this).setTitle("附近的补给")
                .setItems(items, (d, w) -> doNearby(items[w]))
                .setNegativeButton("取消", null).show();
    }

    private void speakWeather() {
        setStatus("正在查天气…");
        Weather.now(myLocation.latitude, myLocation.longitude, (text, city) -> {
            if (text == null || text.isEmpty()) { setStatus("天气查询失败"); return; }
            String msg = (city.isEmpty() ? "" : city + "，") + text;
            setStatus(msg);
            sayRide(msg);
        });
    }

    private void toggleRide() {
        Button b = findViewById(R.id.btnRide);
        b.setAllCaps(false);
        if (rideTracker.isRiding()) {
            RideTracker.Ride r = rideTracker.stop();
            stopFallDetection();
            setRideUi(false);
            b.setText("骑行");
            if (rideHud != null) rideHud.setVisibility(View.GONE);
            if (r != null) {
                showRideSummary(r);
                final java.util.List<LatLng> pts = new java.util.ArrayList<>(r.points);
                if (pts.size() > 2) {
                    Elevation.ascent(pts, (asc, lo, hi) -> {
                        if (asc >= 0) { rideTracker.saveAscent(r, asc); showRideSummary(r); }
                    });
                }
            }
        } else {
            rideTracker.start(myLocation);
            lastKm = 0;
            b.setText("结束");
            updateRideHud();
            setStatus("开始记录骑行");
            setRideUi(true);
            startFallDetection();
            Weather.now(myLocation.latitude, myLocation.longitude, (text, city) -> {
                if (text != null && !text.isEmpty()) {
                    setStatus("开始记录骑行 · " + text);
                    sayRide("开始记录骑行，" + text);
                }
            });
        }
    }

    private void showRideSummary(RideTracker.Ride r) {
        String msg = "距离 " + String.format("%.1f 公里", r.meters / 1000)
                + "　时长 " + fmtClock(r.movingSeconds())
                + "\n均速 " + String.format("%.1f km/h", r.avgKmh())
                + "　最高 " + String.format("%.1f km/h", r.maxKmh)
                + (r.ascent >= 0 ? "　爬升 " + r.ascent + " 米" : "　爬升 计算中…")
                + "\n消耗 " + Math.round(r.kcal(rideTracker.weight())) + " kcal（估算）";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        if (r.points.size() > 1) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            int w = (int) (300 * getResources().getDisplayMetrics().density);
            int h = (int) (110 * getResources().getDisplayMetrics().density);
            iv.setImageBitmap(drawTrack(r.points, w, h));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            box.addView(iv);
        }

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(14);
        tv.setTextColor(0xFF1F1F1F);
        tv.setPadding(0, pad / 2, 0, 0);
        box.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("本次骑行")
                .setView(box)
                .setPositiveButton("好", null)
                .setNeutralButton("导出 GPX", (d, w) -> {
                    try {
                        java.io.File f2 = GpxExporter.write(this, r);
                        setStatus("已导出 " + f2.getName());
                        GpxExporter.share(this, f2);
                    } catch (Throwable t) {
                        setStatus("导出失败：" + t.getMessage());
                    }
                })
                .show();
    }

    /** 把轨迹画成缩略图：蓝线 + 绿起点 + 红终点 */
    private android.graphics.Bitmap drawTrack(java.util.List<LatLng> pts, int w, int h) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                Math.max(w, 1), Math.max(h, 1), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        cv.drawColor(0xFFF2F4F7);
        if (pts == null || pts.size() < 2) return bmp;

        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        for (LatLng p : pts) {
            if (p.latitude < minLat) minLat = p.latitude;
            if (p.latitude > maxLat) maxLat = p.latitude;
            if (p.longitude < minLng) minLng = p.longitude;
            if (p.longitude > maxLng) maxLng = p.longitude;
        }
        double spanLat = Math.max(maxLat - minLat, 1e-6);
        double spanLng = Math.max(maxLng - minLng, 1e-6);
        double scale = Math.min((w - 24) / spanLng, (h - 24) / spanLat);
        double offX = (w - spanLng * scale) / 2;
        double offY = (h - spanLat * scale) / 2;

        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < pts.size(); i++) {
            float x = (float) ((pts.get(i).longitude - minLng) * scale + offX);
            float y = (float) ((maxLat - pts.get(i).latitude) * scale + offY);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        line.setStyle(android.graphics.Paint.Style.STROKE);
        line.setStrokeWidth(6f);
        line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        line.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        line.setColor(0xFF1A73E8);
        cv.drawPath(path, line);

        android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float sx = (float) ((pts.get(0).longitude - minLng) * scale + offX);
        float sy = (float) ((maxLat - pts.get(0).latitude) * scale + offY);
        float ex = (float) ((pts.get(pts.size()-1).longitude - minLng) * scale + offX);
        float ey = (float) ((maxLat - pts.get(pts.size()-1).latitude) * scale + offY);
        dot.setColor(0xFF1E8E3E);
        cv.drawCircle(sx, sy, 9f, dot);
        dot.setColor(0xFFD93025);
        cv.drawCircle(ex, ey, 9f, dot);
        return bmp;
    }

    private void showRideHistory() {
        java.util.List<RideTracker.Ride> list = rideTracker.history();
        if (list.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("骑行记录")
                    .setMessage("还没有记录。点「骑行」开始记录，再点一次结束。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        // 周汇总：本周 vs 上周
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setFirstDayOfWeek(java.util.Calendar.MONDAY);
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long weekStart = cal.getTimeInMillis();
        long lastWeekStart = weekStart - 7L * 24 * 3600 * 1000;
        double thisWeek = 0, lastWeek = 0;
        int weekCount = 0, weekAscent = 0;
        for (RideTracker.Ride r : list) {
            if (r.startAt >= weekStart) {
                thisWeek += r.meters;
                weekCount++;
                if (r.ascent > 0) weekAscent += r.ascent;
            } else if (r.startAt >= lastWeekStart) {
                lastWeek += r.meters;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本周 ").append(weekCount).append(" 次 · ")
          .append(String.format("%.1f km", thisWeek / 1000))
          .append(" · 爬升 ").append(weekAscent).append(" m")
          .append("\n");
        {
            double diff = (thisWeek - lastWeek) / 1000;
            sb.append("上周 ").append(String.format("%.1f km", lastWeek / 1000))
              .append("　").append(diff >= 0 ? "+" : "")
              .append(String.format("%.1f km", diff)).append("\n");
        }
        sb.append("\n");

        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        double total = 0;
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            RideTracker.Ride r = list.get(i);
            total += r.meters;
            sb.append(f.format(new java.util.Date(r.startAt)))
              .append("  ").append(String.format("%.1f km", r.meters / 1000))
              .append("  ").append(fmtClock(r.movingSeconds()))
              .append("  ").append(String.format("%.1f km/h", r.avgKmh()))
              .append(r.ascent >= 0 ? "  爬升" + r.ascent + "m" : "")
              .append("\n");
        }
        if (list.size() > 20) sb.append("…共 ").append(list.size()).append(" 次\n");
        sb.append("\n累计 ").append(String.format("%.1f 公里", total / 1000));
        new MaterialAlertDialogBuilder(this).setTitle("骑行记录").setMessage(sb.toString())
                .setPositiveButton("好", null).show();
    }

    private String fmtClock(long sec) {
        if (sec < 60) return sec + " 秒";
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? h + " 小时 " + m + " 分" : m + " 分钟";
    }

    /** 每个定位更新骑行数据与里程碑播报 */
    private void onRideFix(LatLng cur, double speedMps) {
        if (!rideTracker.isRiding()) return;
        android.util.Log.i("MapMateRide", "fix speed=" + speedMps);
        rideTracker.update(cur, speedMps);
        updateRideHud();
        Button b = findViewById(R.id.btnRide);
        b.setText("结束");
        int km = (int) (rideTracker.meters() / 1000);
        if (km > lastKm && km > 0) {
            lastKm = km;
            sayRide("已骑行" + km + "公里，均速" + Math.round(rideTracker.avgKmh()) + "公里每小时");
        }
    }

    /** 摔倒检测：2.5g 冲击 + 之后 8 秒几乎无位移 */
    private void startFallDetection() {
        try {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sensorManager.registerListener(fallListener, acc, SensorManager.SENSOR_DELAY_NORMAL);
            fallPrompted = false;
            lastImpactAt = 0;
            lastMoveAt = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }

    private void stopFallDetection() {
        try { if (sensorManager != null) sensorManager.unregisterListener(fallListener); } catch (Throwable ignored) {}
    }

    private final SensorEventListener fallListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            double x = e.values[0], y = e.values[1], z = e.values[2];
            double g = Math.sqrt(x * x + y * y + z * z) / 9.81;
            long now = System.currentTimeMillis();
            if (g > 2.5) {
                lastImpactAt = now;
                lastMoveAt = now;
                return;
            }
            if (g > 1.25) lastMoveAt = now;
            if (!fallPrompted && lastImpactAt > 0
                    && now - lastImpactAt > 8000 && now - lastImpactAt < 20000
                    && now - lastMoveAt > 6000) {
                fallPrompted = true;
                promptFall();
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    private void promptFall() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(1500);
        } catch (Throwable ignored) {}
        sayRide("检测到可能的摔倒，如果没事请点我没事");
        new MaterialAlertDialogBuilder(this)
                .setTitle("检测到可能的摔倒")
                .setMessage("8 秒内几乎没有移动。需要帮助吗？")
                .setPositiveButton("我没事", (d, w) -> { fallPrompted = false; lastImpactAt = 0; })
                .setNegativeButton("拨打紧急联系人", (d, w) -> {
                    fallPrompted = false;
                    lastImpactAt = 0;
                    callEmergency();
                })
                .setCancelable(false)
                .show();
    }

    private void callEmergency() {
        if (emergency == null || emergency.trim().isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("未设置紧急联系人")
                    .setMessage("在「设置」里填写手机号，摔倒时才能一键拨出。")
                    .setPositiveButton("好", null).show();
            return;
        }
        try {
            Intent call = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + emergency.trim()));
            startActivity(call);
        } catch (Throwable t) {
            setStatus("拨号失败：" + t.getMessage());
        }
    }

    /** 骑行专注模式：隐藏搜索/输入/方案条，只留地图和 HUD */
    private void setRideUi(boolean riding) {
        int v = riding ? View.GONE : View.VISIBLE;
        try {
            findViewById(R.id.searchRow).setVisibility(v);
            if (riding) altBar.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
        if (rideHud != null) {
            rideHud.setTextSize(riding ? 26f : 20f);
        }
    }

    private void updateRideHud() {
        if (rideHud == null) return;
        if (!rideTracker.isRiding()) { rideHud.setVisibility(View.GONE); return; }
        long sec = rideTracker.movingSeconds();
        String clock = String.format("%d:%02d", sec / 60, sec % 60);
        rideHud.setText(String.format("%.1f km/h%n%.2f km · %s%n均速 %.1f km/h",
                rideTracker.currentKmh(), rideTracker.meters() / 1000, clock, rideTracker.avgKmh()));
        rideHud.setVisibility(View.VISIBLE);
    }

    private void sayRide(String text) {
        if (naviTts != null) naviTts.speak(text, TextToSpeech.QUEUE_ADD, null, "r" + System.currentTimeMillis());
    }

    private void showFavorites() {
        final java.util.List<MemoryStore.Place> favs = memory.favorites();
        if (favs.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("收藏")
                    .setMessage("还没有收藏。长按地图上的位置即可收藏。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] items = new String[favs.size()];
        for (int i = 0; i < favs.size(); i++) {
            MemoryStore.Place p = favs.get(i);
            items[i] = p.name + (p.address.equals(p.name) ? "" : " · " + p.address);
        }
        new MaterialAlertDialogBuilder(this).setTitle("收藏（" + favs.size() + "）")
                .setItems(items, (d, w) -> {
                    MemoryStore.Place p = favs.get(w);
                    planTo(p.name, new LatLng(p.lat, p.lng));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static final String[] CATEGORIES = {
            "咖啡", "餐饮", "便利店", "超市", "加油站", "充电桩", "停车场", "地铁站", "医院", "药店"
    };

    private void showNearbyCategories() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("附近")
                .setItems(CATEGORIES, (d, which) -> doNearby(CATEGORIES[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doNearby(final String category) {
        setStatus("正在找附近的" + category + "…");
        Geocoder.nearby(category, myLocation.latitude, myLocation.longitude, 3000, list -> {
            if (list == null || list.isEmpty()) {
                setStatus("3 公里内没找到" + category);
                return;
            }
            showPoiList("附近的" + category, list);
        });
    }

    /** 结果行：名称 · 评分 · 人均 · 距离 · 地址 */
    /** 结果卡片：名称/评分/人均 一行，地址/距离/营业时间 一行 */
    private void showPoiList(String title, final java.util.List<Geocoder.Poi> list) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        for (int i = 0; i < list.size(); i++) {
            final Geocoder.Poi p = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
            row.setBackgroundColor(i % 2 == 0 ? 0xFFFFFFFF : 0xFFF7F7F7);

            StringBuilder head = new StringBuilder(p.name);
            if (p.rating != null && !p.rating.isEmpty() && !"[]".equals(p.rating)) head.append("  ★").append(p.rating);
            if (p.cost != null && !p.cost.isEmpty() && !"[]".equals(p.cost)) {
                try { head.append("  人均￥").append((int) Double.parseDouble(p.cost)); } catch (Exception ignored) {}
            }
            TextView t1 = new TextView(this);
            t1.setText(head.toString());
            t1.setTextSize(15);
            t1.setTextColor(0xFF222222);

            StringBuilder sub = new StringBuilder();
            if (p.distance > 0) sub.append(p.distance).append(" 米");
            if (p.address != null && !p.address.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(p.address);
            }
            if (p.openTime != null && !p.openTime.isEmpty() && !"[]".equals(p.openTime)) {
                if (sub.length() > 0) sub.append("\n");
                sub.append("营业：").append(p.openTime);
            }
            TextView t2 = new TextView(this);
            t2.setText(sub.toString());
            t2.setTextSize(12);
            t2.setTextColor(0xFF888888);
            t2.setPadding(0, (int) (3 * d), 0, 0);

            row.addView(t1);
            row.addView(t2);
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                planTo(p.name, new LatLng(p.lat, p.lng));
            });
            box.addView(row);
        }

        sectionTitle(box, "云端记忆（可选）");

        android.widget.RadioGroup cloudGroup = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbGist = new android.widget.RadioButton(this);
        rbGist.setText("GitHub Gist");
        rbGist.setId(2001);
        android.widget.RadioButton rbDav = new android.widget.RadioButton(this);
        rbDav.setText("WebDAV（坚果云/群晖，国内更快）");
        rbDav.setId(2002);
        cloudGroup.addView(rbGist);
        cloudGroup.addView(rbDav);
        cloudGroup.check("webdav".equals(cloudType) ? 2002 : 2001);
        box.addView(cloudGroup);

        EditText eToken = field(box, "GitHub Token（只需 gist 权限）", cloudToken, false);
        eToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText eGist = field(box, "备份 ID（首次上传后自动生成）", cloudGist, false);
        EditText eDavUrl = field(box, "WebDAV 文件地址", davUrl, false);
        EditText eDavUser = field(box, "WebDAV 账号", davUser, false);
        EditText eDavPass = field(box, "WebDAV 应用密码", davPass, false);
        eDavPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText eDavPin = field(box, "证书指纹 SHA-256（自签证书必填，可从服务器获取）", davPin, false);

        LinearLayout cloudRow = new LinearLayout(this);
        cloudRow.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton btnUp = new MaterialButton(this);
        btnUp.setText("上传备份");
        btnUp.setTextSize(12);
        btnUp.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton btnDown = new MaterialButton(this);
        btnDown.setText("从云端恢复");
        btnDown.setTextSize(12);
        btnDown.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cloudRow.addView(btnUp);
        cloudRow.addView(btnDown);
        box.addView(cloudRow);

        btnUp.setOnClickListener(v -> {
            saveCloudFields(cloudGroup, eToken, eGist, eDavUrl, eDavUser, eDavPass, eDavPin);
            uploadMemory();
        });
        btnDown.setOnClickListener(v -> {
            saveCloudFields(cloudGroup, eToken, eGist, eDavUrl, eDavUser, eDavPass, eDavPin);
            downloadMemory(eGist, eDavUrl);
        });

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(sv)
                .setNegativeButton("取消", null)
                .create();
        holder[0].show();
    }

    private String poiLabel(Geocoder.Poi p) {
        StringBuilder sb = new StringBuilder(p.name);
        if (p.rating != null && !p.rating.isEmpty() && !"[]".equals(p.rating)) sb.append(" · ★").append(p.rating);
        if (p.cost != null && !p.cost.isEmpty() && !"[]".equals(p.cost)) {
            try { sb.append(" · 人均￥").append((int) Double.parseDouble(p.cost)); } catch (Exception ignored) {}
        }
        if (p.distance > 0) sb.append(" · ").append(p.distance).append("米");
        if (p.address != null && !p.address.isEmpty()) sb.append(" · ").append(p.address);
        return sb.toString();
    }

    private void doSearch(final String keyword) {
        setStatus("搜索: " + keyword);
        Geocoder.search(keyword, myLocation.latitude, myLocation.longitude, list -> {
            if (list == null || list.isEmpty()) {
                setStatus("没搜到「" + keyword + "」");
                return;
            }
            showPoiList("「" + keyword + "」的搜索结果", list);
        });
    }

    /** 调用高德官方导航页：路口放大、车道线、官方播报 */
    private void startProNavi() {
        if (currentDest == null) return;
        try {
            Poi end = new Poi(currentPlace, currentDest, "");
            AmapNaviParams params = new AmapNaviParams(null, null, end,
                    AmapNaviType.DRIVER, AmapPageType.NAVI);
            params.setUseInnerVoice(false);
            AmapNaviPage.getInstance().showRouteActivity(this, params, new NaviVoice());
            setStatus("已启动专业导航");
        } catch (Throwable t) {
            setStatus("专业导航启动失败: " + t.getMessage());
        }
    }

    /** 接住导航 SDK 的播报文本，用系统 TTS 念（绕过语音服务授权） */
    private class NaviVoice implements com.amap.api.navi.INaviInfoCallback {
        @Override public void onGetNavigationText(String text) {
            if (text != null && !text.trim().isEmpty()) {
                String clean = text.replaceAll("[,，]{2,}", "，").replaceAll("^[,，]+", "").trim();
                if (clean.isEmpty()) return;
                android.util.Log.i("MapMateNavi", "speak: " + clean);
                if (naviTts != null) {
                    naviTts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "n" + System.currentTimeMillis());
                }
            }
        }
        @Override public void onArriveDestination(boolean b) { setStatus("已到达目的地"); }
        @Override public void onStopSpeaking() { if (naviTts != null) naviTts.stop(); }
        @Override public void onInitNaviFailure() { setStatus("导航初始化失败"); }
        @Override public void onLocationChange(com.amap.api.navi.model.AMapNaviLocation l) {}
        @Override public void onStartNavi(int type) {}
        @Override public void onCalculateRouteSuccess(int[] ids) {}
        @Override public void onCalculateRouteFailure(int code) { setStatus("算路失败(" + code + ")"); }
        @Override public void onReCalculateRoute(int reason) {}
        @Override public void onExitPage(int code) {}
        @Override public void onStrategyChanged(int s) {}
        @Override public void onArrivedWayPoint(int index) {}
        @Override public void onMapTypeChanged(int t) {}
        @Override public void onNaviDirectionChanged(int d) {}
        @Override public void onDayAndNightModeChanged(int m) {}
        @Override public void onBroadcastModeChanged(int m) {}
        @Override public void onScaleAutoChanged(boolean b) {}
        @Override public android.view.View getCustomMiddleView() { return null; }
        @Override public android.view.View getCustomNaviView() { return null; }
        @Override public android.view.View getCustomNaviBottomView() { return null; }
    }

    private void showRouteDetail() {
        if (currentRoute == null || selectedPath >= currentRoute.paths.size()) return;
        RoutePlanner.Path p = currentRoute.paths.get(selectedPath);
        StringBuilder sb = new StringBuilder();
        sb.append(fmtDistance(p.distance)).append(" · ").append(fmtDuration(p.duration));
        if (p.trafficLights > 0) sb.append(" · ").append(p.trafficLights).append(" 个红绿灯");
        if (p.tolls > 0) sb.append(" · 过路费 ").append(p.tolls).append(" 元");
        if (!p.restriction.isEmpty() && !"0".equals(p.restriction)) sb.append("\n⚠ ").append(p.restriction);
        sb.append("\n\n");
        int i = 1;
        for (RoutePlanner.Step s : p.steps) {
            sb.append(i++).append(". ").append(s.instruction)
              .append("（").append(s.distance).append(" 米）\n");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("路线详情")
                .setMessage(sb.toString())
                .setPositiveButton("专业导航", (d, w) -> startProNavi())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showMemory() {
        StringBuilder sb = new StringBuilder();
        sb.append("长期记忆：").append(memory.facts().size()).append(" 条\n");
        if (!memory.summary().isEmpty()) {
            sb.append("\n[滚动摘要]\n").append(memory.summary()).append("\n");
        }
        sb.append("\n[具体事实]\n");
        if (memory.facts().isEmpty()) {
            sb.append("(空) 说\"记住……\"就会写进来\n");
        } else {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            for (MemoryStore.Fact fa : memory.facts()) {
                sb.append("• ").append(fa.content).append("\n");
                sb.append("   ").append(fmt.format(new java.util.Date(fa.ts)));
                if (!fa.sourceQuote.isEmpty()) {
                    String q = fa.sourceQuote.length() > 20 ? fa.sourceQuote.substring(0, 20) + "…" : fa.sourceQuote;
                    sb.append("　来自「").append(q).append("」");
                }
                sb.append("\n");
            }
        }
        if (!memory.problem().isEmpty()) sb.append("\n⚠ ").append(memory.problem());
        sb.append("\n（记忆可在「菜单」→「小德记得的事」查看）");

        new MaterialAlertDialogBuilder(this)
                .setTitle("小德记得的事")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private EditText field(LinearLayout box, String hint, String value, boolean multiline) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint(hint);
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setBoxCornerRadii(12, 12, 12, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        til.setLayoutParams(lp);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14);
        et.setSingleLine(!multiline);
        til.addView(et);
        box.addView(til);
        return et;
    }

    private TextView sectionTitle(LinearLayout box, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF1A73E8);
        tv.setPadding(0, (int) (18 * getResources().getDisplayMetrics().density), 0,
                (int) (4 * getResources().getDisplayMetrics().density));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(tv);
        return tv;
    }

    private void showSettings() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (4 * d), (int) (20 * d), (int) (12 * d));

        sectionTitle(box, "连接方式");

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbDirect = new android.widget.RadioButton(this);
        rbDirect.setText("直连 DeepSeek（推荐，不需要电脑）");
        rbDirect.setId(1001);
        android.widget.RadioButton rbServer = new android.widget.RadioButton(this);
        rbServer.setText("连接电脑服务端（需要同一 WiFi）");
        rbServer.setId(1002);
        group.addView(rbDirect);
        group.addView(rbServer);
        group.check("server".equals(mode) ? 1002 : 1001);
        box.addView(group);

        sectionTitle(box, "直连参数");
        EditText eKey = field(box, "DeepSeek API Key（sk-...）", apiKey, false);
        eKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText eBase = field(box, "API 地址", apiBase, false);
        EditText eModel = field(box, "模型", apiModel, false);

        sectionTitle(box, "服务端地址");
        EditText eWs = field(box, "例如 ws://192.168.1.5:8000/xiaozhi/v1/", serverWs, false);

        sectionTitle(box, "骑行与安全（可选）");
        EditText eWeight = field(box, "体重 kg（算卡路里）", String.valueOf((int) weightKg), false);
        EditText eEmergency = field(box, "紧急联系人手机号", emergency, false);
        sectionTitle(box, "驾车（可选）");
        EditText ePlate = field(box, "车牌号（规避限行）", plate, false);

        sectionTitle(box, "云端记忆（可选）");

        android.widget.RadioGroup cloudGroup = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbGist = new android.widget.RadioButton(this);
        rbGist.setText("GitHub Gist");
        rbGist.setId(2001);
        android.widget.RadioButton rbDav = new android.widget.RadioButton(this);
        rbDav.setText("WebDAV（坚果云/群晖，国内更快）");
        rbDav.setId(2002);
        cloudGroup.addView(rbGist);
        cloudGroup.addView(rbDav);
        cloudGroup.check("webdav".equals(cloudType) ? 2002 : 2001);
        box.addView(cloudGroup);

        EditText eToken = field(box, "GitHub Token（只需 gist 权限）", cloudToken, false);
        eToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText eGist = field(box, "备份 ID（首次上传后自动生成）", cloudGist, false);
        EditText eDavUrl = field(box, "WebDAV 文件地址", davUrl, false);
        EditText eDavUser = field(box, "WebDAV 账号", davUser, false);
        EditText eDavPass = field(box, "WebDAV 应用密码", davPass, false);
        eDavPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText eDavPin = field(box, "证书指纹 SHA-256（自签证书必填，可从服务器获取）", davPin, false);

        LinearLayout cloudRow = new LinearLayout(this);
        cloudRow.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton btnUp = new MaterialButton(this);
        btnUp.setText("上传备份");
        btnUp.setTextSize(12);
        btnUp.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton btnDown = new MaterialButton(this);
        btnDown.setText("从云端恢复");
        btnDown.setTextSize(12);
        btnDown.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cloudRow.addView(btnUp);
        cloudRow.addView(btnDown);
        box.addView(cloudRow);

        btnUp.setOnClickListener(v -> {
            saveCloudFields(cloudGroup, eToken, eGist, eDavUrl, eDavUser, eDavPass, eDavPin);
            uploadMemory();
        });
        btnDown.setOnClickListener(v -> {
            saveCloudFields(cloudGroup, eToken, eGist, eDavUrl, eDavUser, eDavPass, eDavPin);
            downloadMemory(eGist, eDavUrl);
        });

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        new MaterialAlertDialogBuilder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("保存", (dlg, w) -> {
                    mode = group.getCheckedRadioButtonId() == 1002 ? "server" : "direct";
                    apiKey = eKey.getText().toString().trim();
                    apiBase = eBase.getText().toString().trim();
                    apiModel = eModel.getText().toString().trim();
                    serverWs = eWs.getText().toString().trim();
                    emergency = eEmergency.getText().toString().trim();
                    plate = ePlate.getText().toString().trim();
                    try { weightKg = Double.parseDouble(eWeight.getText().toString().trim()); } catch (Exception ignored) {}
                    if (apiBase.isEmpty()) apiBase = DEFAULT_BASE;
                    if (apiModel.isEmpty()) apiModel = DEFAULT_MODEL;
                    if (serverWs.isEmpty()) serverWs = DEFAULT_WS;
                    sp.edit()
                            .putString("mode", mode)
                            .putString("apiKey", apiKey)
                            .putString("apiBase", apiBase)
                            .putString("apiModel", apiModel)
                            .putString("ws", serverWs)
                            .putString("emergency", emergency)
                            .putString("plate", plate)
                            .putFloat("weight", (float) weightKg)
                            .apply();
                    rideTracker.setWeight(weightKg);
                    setupAgentOrServer();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String readInternal(String name) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), name);
            if (!f.exists()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    private void writeInternal(String name, String content) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), name))) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void saveCloudFields(android.widget.RadioGroup group, EditText eToken, EditText eGist,
                                 EditText eUrl, EditText eUser, EditText ePass, EditText ePin) {
        cloudType = group.getCheckedRadioButtonId() == 2002 ? "webdav" : "gist";
        cloudToken = eToken.getText().toString().trim();
        cloudGist = eGist.getText().toString().trim();
        davUrl = eUrl.getText().toString().trim();
        davUser = eUser.getText().toString().trim();
        davPass = ePass.getText().toString().trim();
        davPin = ePin.getText().toString().trim();
        sp.edit()
                .putString("cloudType", cloudType)
                .putString("cloudToken", cloudToken)
                .putString("cloudGist", cloudGist)
                .putString("davUrl", davUrl)
                .putString("davUser", davUser)
                .putString("davPass", davPass)
                .putString("davPin", davPin)
                .apply();
    }

    private void uploadMemory() {
        if ("webdav".equals(cloudType)) { uploadDav(); return; }
        if (cloudToken.isEmpty()) { setStatus("先填 GitHub Token"); return; }
        try {
            org.json.JSONObject payload = new org.json.JSONObject()
                    .put("memory", readInternal("memory.json"))
                    .put("rides", readInternal("rides.json"))
                    .put("history", HistoryArchive.tailJson(this, 300))
                    .put("at", System.currentTimeMillis());
            setStatus("正在上传…");
            CloudSync.upload(cloudToken, cloudGist, payload.toString(), (ok, msg, gid, content) -> {
                if (ok) {
                    cloudGist = gid;
                    sp.edit().putString("cloudGist", gid).apply();
                    setStatus("已上传 · 备份 ID " + gid.substring(0, Math.min(8, gid.length())) + "…");
                } else {
                    setStatus(msg);
                }
            });
        } catch (Throwable t) {
            setStatus("上传失败：" + t.getMessage());
        }
    }

    private String buildPayload() throws Exception {
        return new org.json.JSONObject()
                .put("memory", readInternal("memory.json"))
                .put("rides", readInternal("rides.json"))
                .put("history", HistoryArchive.tailJson(this, 300))
                .put("at", System.currentTimeMillis())
                .toString();
    }

    private void uploadDav() {
        if (davUrl.isEmpty()) { setStatus("先填 WebDAV 地址"); return; }
        try {
            setStatus("正在上传…");
            CloudSync.uploadWebdav(davUrl, davUser, davPass, davPin, buildPayload(),
                    (ok, msg, id, content) -> setStatus(msg));
        } catch (Throwable t) { setStatus("上传失败：" + t.getMessage()); }
    }

    private void downloadDav(EditText urlField) {
        if (davUrl.isEmpty()) { setStatus("先填 WebDAV 地址"); return; }
        setStatus("正在下载…");
        CloudSync.downloadWebdav(davUrl, davUser, davPass, davPin, (ok, msg, id, content) -> {
            if (!ok) { setStatus(msg); return; }
            applyRestore(content, urlField);
        });
    }

    private void applyRestore(String content, EditText field2) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(content);
            String mem = o.optString("memory", "");
            if (!mem.isEmpty()) writeInternal("memory.json", mem);
            String rides = o.optString("rides", "");
            if (!rides.isEmpty()) writeInternal("rides.json", rides);
            memory = new MemoryStore(this);
            rideTracker = new RideTracker(this);
            setStatus("已从云端恢复记忆");
        } catch (Throwable t) {
            setStatus("恢复失败：" + t.getMessage());
        }
    }

    private void downloadMemory(EditText gistField, EditText davField) {
        if ("webdav".equals(cloudType)) { downloadDav(davField); return; }
        if (cloudToken.isEmpty()) { setStatus("先填 GitHub Token"); return; }
        setStatus("正在下载…");
        CloudSync.download(cloudToken, cloudGist, (ok, msg, gid, content) -> {
            if (!ok) { setStatus(msg); return; }
            try {
                org.json.JSONObject o = new org.json.JSONObject(content);
                String mem = o.optString("memory", "");
                if (!mem.isEmpty()) writeInternal("memory.json", mem);
                String rides = o.optString("rides", "");
                if (!rides.isEmpty()) writeInternal("rides.json", rides);
                applyRestore(content, gistField);
                if (gistField != null && gid != null && !gid.isEmpty()) gistField.setText(gid);
            } catch (Throwable t) {
                setStatus("恢复失败：" + t.getMessage());
            }
        });
    }

    /** 没配置任何连接方式时的引导 */
    private void maybeOnboard() {
        if (sp.getBoolean("onboarded", false)) return;
        sp.edit().putBoolean("onboarded", true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle("先配置一下")
                .setMessage("现在是直连模式：填入 DeepSeek API Key 就能用，不需要电脑。\n\n"
                        + "如果要用电脑上的语音服务，把连接方式改成「服务端」并填电脑的局域网地址。")
                .setPositiveButton("去设置", (d, w) -> showSettings())
                .setNegativeButton("稍后", null)
                .show();
    }
    // ---------- 输入 ----------

    /** 找出手机里任何能用的语音识别服务（含非默认的） */
    private android.content.ComponentName findRecognizer() {
        try {
            android.content.Intent intent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            java.util.List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentServices(intent, 0);
            if (list != null && !list.isEmpty()) {
                android.content.pm.ServiceInfo si = list.get(0).serviceInfo;
                return new android.content.ComponentName(si.packageName, si.name);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void noRecognizer() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("这台手机没有可用的语音识别")
                .setMessage("语音识别依赖系统服务。你的手机上没有找到，可能是精简版系统。\n\n"
                        + "三个办法：\n"
                        + "1. 先用下面的输入框打字，功能完全一样\n"
                        + "2. 装一个带语音服务的输入法（如讯飞、搜狗）\n"
                        + "3. 我可以接入云端语音识别（阿里/腾讯/讯飞），需要你提供一个 Key")
                .setPositiveButton("知道了", null)
                .show();
        setStatus("语音识别不可用，请用输入框");
    }

    private void startVoiceInput() {
        if (speech == null) {
            android.content.ComponentName svc = findRecognizer();
            if (svc != null) {
                speech = SpeechRecognizer.createSpeechRecognizer(this, svc);
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speech = SpeechRecognizer.createSpeechRecognizer(this);
            } else if (android.os.Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                noRecognizer();
                return;
            }
            speech.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { setStatus("请说…"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { setStatus("识别中…"); }
                @Override public void onError(int error) { setStatus("识别失败(" + error + ")，请用输入框"); }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) handleUserText(list.get(0));
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        speech.startListening(i);
    }

    private void stopVoiceInput() {
        if (speech != null) speech.stopListening();
    }

    private void handleUserText(String text) {
        setStatus("你说: " + text);
        if (agent != null) {
            agent.send(text, new Agent.Callback() {
                @Override public void onReply(String reply) {
                    runOnUiThread(() -> {
                        setStatusLong("小德: " + reply);
                        speak(reply);
                    });
                }
                @Override public void onError(String message) {
                    String m = message == null ? "" : message;
                    if (m.contains("401") || m.contains("Authentication") || m.contains("invalid")) {
                        setStatusSticky("API Key 无效 · 点这里去设置");
                        status.setOnClickListener(v -> showSettings());
                        return;
                    }
                    if (m.contains("timeout") || m.contains("resolve host")
                            || m.contains("connect") || m.contains("Network")) {
                        setStatusSticky("网络连接失败");
                        return;
                    }
                    android.util.Log.w("MapMateAgent", "raw: " + m);
                    setStatusSticky("模型暂时不可用");
                }
            });
        } else if (voice != null) {
            voice.sendText(text);
        } else {
            setStatus("未配置 API Key");
            new MaterialAlertDialogBuilder(this)
                    .setTitle("还没配置 API Key")
                    .setMessage("直连模式需要你自己的 DeepSeek Key，不配置就不会发起任何网络连接。\n\n"
                            + "去「设置」→ 连接方式选「直连 DeepSeek」→ 粘贴 sk- 开头的 Key → 保存。")
                    .setPositiveButton("去设置", (d, w) -> showSettings())
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void speak(String text) {
        try {
            if (replyTts != null) replyTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r" + System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    // ---------- 语音客户端回调（服务端模式） ----------

    @Override public void onStatus(String s) { setStatus(s); }
    @Override public void onAssistantText(String text) { setStatus("小德: " + text); }
    @Override public void onUserText(String text) { setStatus("你说: " + text); }

    @Override
    public void onIotCommand(String method, String place) {
        if ("stop_navigation".equals(method)) { doStopNavigation(); return; }
        if ("navigate".equals(method)) doNavigate(place);
    }

    // ---------- 地图动作 ----------

    private void doStopNavigation() {
        runOnUiThread(() -> {
            if (guidance != null) guidance.stop();
            aMap.clear();
            routeInfo.setVisibility(TextView.GONE);
            setStatus("导航已结束");
        });
    }

    private void doNavigate(String place) {
        doNavigate(place, "", "");
    }

    private void doNavigate(String place, String via, String modeStr) {
        if (place == null || place.isEmpty()) return;
        if (modeStr != null && !modeStr.isEmpty()) currentMode = RoutePlanner.Mode.from(modeStr);
        if (via != null && !via.isEmpty()) {
            String[] parts = via.split("[,，、;；]");
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String s : parts) if (!s.trim().isEmpty()) names.add(s.trim());
            if (!names.isEmpty()) { doNavigateWithVia(place, names); return; }
        }

        MemoryStore.Place saved = memory.findPlaceByText(place);
        if (saved != null && saved.lat != 0) {
            final LatLng dest = new LatLng(saved.lat, saved.lng);
            runOnUiThread(() -> setStatus("去" + place + "（" + saved.address + "）"));
            planTo(place, dest);
            return;
        }

        runOnUiThread(() -> setStatus("正在解析: " + place));
        LatLng bias = aMap.getCameraPosition().target;
        Geocoder.resolve(place, bias.latitude, bias.longitude, (lat, lng, name) -> {
            if (Double.isNaN(lat)) {
                setStatus("没找到 " + place);
                return;
            }
            LatLng dest = new LatLng(lat, lng);
            setStatus("正在规划路线…");
            planTo(place, dest);
        });
    }

    private void drawRoute(String place, LatLng dest, RoutePlanner.Route route) {
        aMap.clear();
        aMap.addMarker(new MarkerOptions().position(dest).title(place));

        if (route == null || route.paths.isEmpty()) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(dest, 15f));
            setStatus("已定位到 " + place + "（路线规划失败）");
            return;
        }

        android.util.Log.i("MapMate", "dest " + dest.latitude + "," + dest.longitude);

        currentPlace = place;
        currentDest = dest;
        currentRoute = route;
        currentMode = route.mode;
        centeredOnUser = true;
        findViewById(R.id.modeRow).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnStartNav).setVisibility(View.VISIBLE);
        updateModeButtons();
        showAlternatives(route);
        selectPath(0);
    }

    private void renderRouteInfo(String guide) {
        if (guide != null) lastGuide = guide;
        String text = "前往 " + currentPlace + " · " + routeSummary
                + (routeAscent >= 0 ? " · 爬升 " + routeAscent + " 米" : "")
                + "\n" + lastGuide;
        routeInfo.setText(text);
        routeInfo.setVisibility(TextView.VISIBLE);
    }

    /** 高德同款配色：畅通绿、缓行黄、拥堵红 */
    private int colorOf(String status) {
        if (status == null) return 0xFF1A73E8;
        if (status.contains("拥堵")) return 0xFFE53935;
        if (status.contains("缓行")) return 0xFFFFC107;
        if (status.contains("畅通")) return 0xFF43A047;
        return 0xFF1A73E8;   // 未知路况按主路线品牌色画
    }

    private void showAlternatives(RoutePlanner.Route route) {
        altBar.removeAllViews();
        if (rideTracker != null && rideTracker.isRiding()) { altBar.setVisibility(View.GONE); return; }
        altBar.setVisibility(View.VISIBLE);
        for (int i = 0; i < route.paths.size(); i++) {
            RoutePlanner.Path p = route.paths.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText((i == 0 ? "推荐 " : "方案" + (i + 1) + " ") + fmtDuration(p.duration) + "·" + fmtDistance(p.distance));
            b.setTextSize(11);
            b.setSingleLine(true);
            b.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
            b.setLayoutParams(lp);
            b.setMinWidth((int) (104 * getResources().getDisplayMetrics().density));
            final int idx = i;
            b.setOnClickListener(v -> selectPath(idx));
            altBar.addView(b);
        }

    }

    private void setMode(RoutePlanner.Mode m) {
        if (currentMode == m) return;
        currentMode = m;
        updateModeButtons();
        setStatus("切换为" + m.label);
        if (currentDest != null) planTo(currentPlace, currentDest);
    }

    private void updateModeButtons() {
        Button d = findViewById(R.id.btnModeDrive);
        Button b = findViewById(R.id.btnModeBike);
        Button w = findViewById(R.id.btnModeWalk);
        int on = 0x331A73E8, off = 0x00000000;
        d.setBackgroundTintList(android.content.res.ColorStateList.valueOf(currentMode == RoutePlanner.Mode.DRIVE ? on : off));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(currentMode == RoutePlanner.Mode.BIKE ? on : off));
        w.setBackgroundTintList(android.content.res.ColorStateList.valueOf(currentMode == RoutePlanner.Mode.WALK ? on : off));
        d.setTypeface(null, currentMode == RoutePlanner.Mode.DRIVE ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setTypeface(null, currentMode == RoutePlanner.Mode.BIKE ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        w.setTypeface(null, currentMode == RoutePlanner.Mode.WALK ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private boolean collapsed = false;
    private boolean navigating = false;
    private double navRemainM = 0;
    private double navSpeedMps = 12.0;
    private long navStartedAt = 0;

    /** 进入导航模式：隐藏面板，显示转向条与剩余信息 */
    private void startNavigation() {
        if (currentRoute == null) { setStatus("先规划一条路线"); return; }
        navigating = true;
        navStartedAt = System.currentTimeMillis();
        findViewById(R.id.navTop).setVisibility(View.VISIBLE);
        findViewById(R.id.navBottom).setVisibility(View.VISIBLE);
        findViewById(R.id.searchRow).setVisibility(View.GONE);
        findViewById(R.id.bottomCard).setVisibility(View.GONE);
        following = true;
        setStatus("导航已开始");
    }

    private void endNavigation() {
        navigating = false;
        findViewById(R.id.navTop).setVisibility(View.GONE);
        findViewById(R.id.navBottom).setVisibility(View.GONE);
        findViewById(R.id.searchRow).setVisibility(View.VISIBLE);
        findViewById(R.id.bottomCard).setVisibility(View.VISIBLE);
        following = false;
        setStatus("导航已结束");
    }

    private String arrowFor(String ins) {
        if (ins == null) return "⬆";
        if (ins.contains("左转") || ins.contains("左前方")) return "⬅";
        if (ins.contains("右转") || ins.contains("右前方")) return "➡";
        if (ins.contains("掉头")) return "⤵";
        if (ins.contains("到达")) return "🏁";
        return "⬆";
    }

    private String fmtNavDistance(double m) {
        if (m >= 1000) return String.format("%.1f 公里", m / 1000);
        return Math.max(1, Math.round(m)) + " 米";
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        int v = collapsed ? View.GONE : View.VISIBLE;
        findViewById(R.id.altBar).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        findViewById(R.id.modeRow).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        findViewById(R.id.inputRow).setVisibility(v);
        findViewById(R.id.controlsRow).setVisibility(v);
        ((Button) findViewById(R.id.btnCollapse)).setText(collapsed ? "展开" : "收起");
    }

    private void selectPath(int idx) {
        if (currentRoute == null || idx < 0 || idx >= currentRoute.paths.size()) return;
        selectedPath = idx;
        final RoutePlanner.Path p = currentRoute.paths.get(idx);
        final String place = currentPlace;
        final LatLng dest = currentDest;

        aMap.clear();
        aMap.addMarker(new MarkerOptions().position(dest).title(place));
        for (int i = 0; i < currentRoute.paths.size(); i++) {
            if (i == idx) continue;
            aMap.addPolyline(new PolylineOptions().addAll(currentRoute.paths.get(i).points)
                    .width(12f).color(0x889E9E9E));
        }
        boolean anyKnown = false;
        for (RoutePlanner.Tmc t : p.tmcs) {
            String s = t.status == null ? "" : t.status;
            if (s.contains("畅") || s.contains("缓") || s.contains("拥")) { anyKnown = true; break; }
        }
        if (anyKnown) {
            for (RoutePlanner.Tmc t : p.tmcs) {
                aMap.addPolyline(new PolylineOptions().addAll(t.points).width(18f).color(colorOf(t.status)));
            }
        } else {
            // 没有真实路况数据时，整条主路线用品牌蓝，避免和灰色备选线混淆
            aMap.addPolyline(new PolylineOptions().addAll(p.points).width(20f).color(0xFF1A73E8)
                    .zIndex(10));
        }

        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng pt : p.points) b.include(pt);
        int topPad = 170;
        int bottomPad = 60;
        try {
            View bc = findViewById(R.id.bottomCard);
            if (bc != null && bc.getHeight() > 0) bottomPad = bc.getHeight() + 24;
        } catch (Throwable ignored) {}
        final float shift = -bottomPad / 2f;
        aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120), 600,
                new com.amap.api.maps.AMap.CancelableCallback() {
                    @Override public void onFinish() {
                        // 缩放完成后再上移，避免两次相机更新互相打断
                        aMap.animateCamera(CameraUpdateFactory.scrollBy(0, shift), 300, null);
                    }
                    @Override public void onCancel() {}
                });

        navSpeedMps = p.duration > 0 ? Math.max(2.0, p.distance / (double) p.duration) : 12.0;
        final String summary = (currentMode == RoutePlanner.Mode.DRIVE ? "" : currentMode.label + " · ")
                + fmtDistance(p.distance) + " · 约 " + fmtDuration(p.duration)
                + (p.trafficLights > 0 ? " · " + p.trafficLights + " 个红绿灯" : "")
                + (p.tolls > 0 ? " · 过路费 " + p.tolls + " 元" : "");
        routeSummary = summary;
        routeAscent = -1;
        lastGuide = "点「定位」可跟随";
        renderRouteInfo(null);
        setStatus("已规划: " + summary);
        following = false;

        if (currentMode == RoutePlanner.Mode.BIKE) {
            Elevation.ascent(p.points, (asc, lo, hi) -> {
                if (asc >= 0) {
                    routeAscent = asc;
                    renderRouteInfo(null);
                }
            });
        }

        guidance.start(this, p, dest, new Guidance.Listener() {
            @Override public void onGuide(String text) {
                renderRouteInfo(text);
            }
            @Override public void onProgress(double remain, int nextDist, String ins) {
                navRemainM = remain;
                if (!navigating) return;
                TextView d = findViewById(R.id.navDistance);
                TextView i = findViewById(R.id.navInstruction);
                TextView r = findViewById(R.id.navRemain);
                TextView a = findViewById(R.id.navArrow);
                if (d != null) d.setText(fmtNavDistance(nextDist));
                if (i != null) i.setText(ins);
                if (a != null) a.setText(arrowFor(ins));
                if (r != null) {
                    long sec = Math.max(0, (long) (remain / Math.max(2.0, navSpeedMps)));
                    r.setText("剩余 " + fmtNavDistance(remain) + " · 约 " + fmtDuration((int) sec));
                }
                // 相机跟随并旋转到车头方向
                if (hasCompass && guidePos != null) {
                    com.amap.api.maps.model.CameraPosition cp =
                            new com.amap.api.maps.model.CameraPosition.Builder()
                                    .target(guidePos)
                                    .zoom(18f)
                                    .tilt(45f)
                                    .bearing(compassAzimuth)
                                    .build();
                    aMap.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 600, null);
                }
            }
            @Override public void onPosition(LatLng cur) {
                guidePos = cur;
                moveMyMarker(cur);
                if (following) aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(cur, 17f), 800, null);
            }
            @Override public void onArrived() {
                following = false;
                routeInfo.setText("已到达 " + place);
                setStatus("已到达 " + place);
            }
            @Override public void onOffRoute(LatLng cur) {
                following = true;
                myLocation = cur;
                replan();
            }
        });
    }

    /** 依次解析途经点，再一次性规划 */
    private void doNavigateWithVia(String place, java.util.List<String> vias) {
        setStatus("正在解析途经点…");
        final java.util.List<LatLng> points = new java.util.ArrayList<>();
        resolveNextVia(vias, 0, points, place);
    }

    private void resolveNextVia(final java.util.List<String> vias, final int idx,
                                final java.util.List<LatLng> acc, final String place) {
        if (idx >= vias.size()) {
            resolveFinalWithVia(place, acc);
            return;
        }
        Geocoder.resolve(vias.get(idx), myLocation.latitude, myLocation.longitude, (lat, lng, name) -> {
            if (!Double.isNaN(lat)) acc.add(new LatLng(lat, lng));
            resolveNextVia(vias, idx + 1, acc, place);
        });
    }

    private void resolveFinalWithVia(final String place, final java.util.List<LatLng> vias) {
        Geocoder.resolve(place, myLocation.latitude, myLocation.longitude, (lat, lng, name) -> {
            if (Double.isNaN(lat)) { setStatus("没找到 " + place); return; }
            final LatLng dest = new LatLng(lat, lng);
            setStatus("正在规划" + (vias.isEmpty() ? "" : "（含 " + vias.size() + " 个途经点）"));
            RoutePlanner.plan(myLocation, dest, currentMode, vias, plate, route -> drawRoute(place, dest, route));
        });
    }

    private void replan() {
        if (currentDest == null) return;
        setStatus("重新规划中…");
        RoutePlanner.plan(myLocation, currentDest, currentMode, null, plate,
                route -> drawRoute(currentPlace, currentDest, route));
    }
    private String fmtDistance(int m) {
        return m >= 1000 ? String.format("%.1f 公里", m / 1000.0) : m + " 米";
    }

    private String fmtDuration(int s) {
        int min = Math.max(1, s / 60);
        return min >= 60 ? (min / 60) + " 小时 " + (min % 60) + " 分钟" : min + " 分钟";
    }

    private final Runnable hideStatus = () -> {
        if (status != null) status.setVisibility(View.INVISIBLE);
    };

    /** 普通提示：4 秒后自动消失 */
    private void setStatus(String s) {
        runOnUiThread(() -> {
            if (status == null) return;
            status.setText(s);
            status.setVisibility(View.VISIBLE);
            status.removeCallbacks(hideStatus);
            status.postDelayed(hideStatus, 4000);
        });
    }

    /** 长提示：8 秒（助手回复用） */
    private void setStatusLong(String s) {
        runOnUiThread(() -> {
            if (status == null) return;
            status.setText(s);
            status.setVisibility(View.VISIBLE);
            status.removeCallbacks(hideStatus);
            status.postDelayed(hideStatus, 8000);
        });
    }

    /** 错误：常驻直到处理 */
    private void setStatusSticky(String s) {
        runOnUiThread(() -> {
            if (status == null) return;
            status.removeCallbacks(hideStatus);
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        });
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] perms = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO};
            boolean need = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { need = true; break; }
            }
            if (need) requestPermissions(perms, 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 100) return;
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok) {
            setStatus("未授予定位权限 · 点这里开启");
            status.setOnClickListener(v -> openAppSettings());
        } else {
            startLocationUpdates();
        }
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            setStatus("打不开系统设置：" + t.getMessage());
        }
    }

    private long lastBackAt = 0;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        String search = intent.getStringExtra("search");
        if (search != null && !search.isEmpty()) { doSearch(search); return; }
        String cmd = intent.getStringExtra("cmd");
        if (cmd != null && !cmd.isEmpty()) handleUserText(cmd);
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackAt = now;
        setStatus("再按一次退出");
    }

    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); if (mapView != null) mapView.onPause(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (lm != null && locListener != null) lm.removeUpdates(locListener); } catch (Throwable ignored) {}
        try { if (sensorManager != null) sensorManager.unregisterListener(fallListener); } catch (Throwable ignored) {}
        try { if (sensorManager != null) sensorManager.unregisterListener(compassListener); } catch (Throwable ignored) {}
        if (guidance != null) guidance.shutdown();
        if (speech != null) speech.destroy();
        if (replyTts != null) { replyTts.stop(); replyTts.shutdown(); }
        if (voice != null) voice.close();
        if (audio != null) audio.release();
        if (mapView != null) mapView.onDestroy();
    }

    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
}