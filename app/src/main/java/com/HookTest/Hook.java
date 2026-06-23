package com.zy.jhpjs;

import static android.content.Context.WINDOW_SERVICE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.media.ExifInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HookTest";
    private static final String PREFS_NAME = "hookdata";

    // 全局状态
    private static boolean locationEnabled = false;
    private static boolean wifiEnabled = false;
    private static boolean bleEnabled = false;
    private static boolean cameraEnabled = false;
    private static int cameraMode = 0; // 0=单张, 1=多张循环
    private static String customLat = "";
    private static String customLng = "";
    private static String customWifiSSID = "";
    private static String customWifiBSSID = "";
    private static String customBleName = "";
    private static String customBleAddress = "";
    private static String customBleData = "";

    // 相机替换相关
    private static String singleImagePath = "";
    private static List<String> multiImagePaths = new ArrayList<>();
    private static int currentImageIndex = 0;
    private static String mapSearchKeyword = "";
    private static List<JSONObject> mapSearchResults = new ArrayList<>();

    // 扫描结果缓存
    private static List<String> scannedWifiList = new ArrayList<>();
    private static List<String> scannedBleList = new ArrayList<>();
    private static List<String> scannedBleDataList = new ArrayList<>();

    // 面板中的EditText引用
    private static EditText wifiSsidEdit;
    private static EditText wifiBssidEdit;
    private static EditText bleNameEdit;
    private static EditText bleAddrEdit;
    private static EditText bleDataEdit;
    private static EditText latEdit;
    private static EditText lngEdit;
    private static TextView imageStatusText;
    private static LinearLayout imagePreviewContainer;

    // 悬浮窗相关
    private static WindowManager windowManager;
    private static View floatView;
    private static View panelView;
    private static boolean isPanelShowing = false;
    private static int statusBarHeight = 0;

    private static Context appContext;
    private static Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.alibaba.android.rimet")) {
            return;
        }

        Log.e(TAG, "包名匹配，开始Hook");

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                appContext = (Context) param.args[0];
                Log.e(TAG, "获取到Context: " + appContext);
                loadPrefs();
                hookAMapLocation(lpparam);
                hookDingTalkWiFi(lpparam);
                hookDingTalkBluetooth(lpparam);
                hookCamera(lpparam);
            }
        });

        XposedHelpers.findAndHookMethod("com.alibaba.android.rimet.biz.LaunchHomeActivity",
                lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Activity activity = (Activity) param.thisObject;
                Log.e(TAG, "LaunchHomeActivity onCreate");
                uiHandler.postDelayed(() -> {
                    try {
                        showFloatWindow(activity);
                    } catch (Throwable t) {
                        Log.e(TAG, "显示悬浮窗失败", t);
                    }
                }, 500);
            }
        });
    }

    // ======================== 悬浮窗UI ========================

    @SuppressLint("ClickableViewAccessibility")
    private void showFloatWindow(Context ctx) {
        if (floatView != null) return;
        if (windowManager == null) {
            windowManager = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
        }

        int resourceId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = ctx.getResources().getDimensionPixelSize(resourceId);
        }

        final LinearLayout floatBtn = new LinearLayout(ctx);
        floatBtn.setOrientation(LinearLayout.HORIZONTAL);
        floatBtn.setBackgroundColor(0xCC000000);
        floatBtn.setPadding(16, 8, 16, 8);
        floatBtn.setGravity(Gravity.CENTER);

        TextView btnText = new TextView(ctx);
        btnText.setText("⚙ Hook");
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        floatBtn.addView(btnText);

        WindowManager.LayoutParams floatParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = 0;
        floatParams.y = statusBarHeight + 50;

        floatBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long startTime = 0;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = floatParams.x;
                        initialY = floatParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        startTime = System.currentTimeMillis();
                        isDragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging = true;
                        }
                        floatParams.x = initialX + dx;
                        floatParams.y = initialY + dy;
                        if (windowManager != null && floatView != null) {
                            windowManager.updateViewLayout(floatView, floatParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging && System.currentTimeMillis() - startTime < 300) {
                            togglePanel(ctx);
                        }
                        return false;
                }
                return false;
            }
        });

        try {
            windowManager.addView(floatBtn, floatParams);
            floatView = floatBtn;
            Log.e(TAG, "悬浮按钮显示成功");
        } catch (Throwable t) {
            Log.e(TAG, "悬浮按钮显示失败", t);
        }
    }

    private int getWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void togglePanel(Context ctx) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(ctx);
        }
    }

    private void hidePanel() {
        if (panelView != null && windowManager != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Throwable t) {
                Log.e(TAG, "移除面板失败", t);
            }
            panelView = null;
            isPanelShowing = false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPanel(Context ctx) {
        if (isPanelShowing) return;

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int panelWidth = Math.min(900, (int) (screenWidth * 0.9));

        LinearLayout mainContainer = new LinearLayout(ctx);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setBackgroundColor(0xE0FFFFFF);
        mainContainer.setPadding(16, 16, 16, 16);

        LinearLayout titleBar = new LinearLayout(ctx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleBar.setLayoutParams(titleParams);

        TextView titleText = new TextView(ctx);
        titleText.setText("Hook 设置面板");
        titleText.setTextColor(0xFF000000);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleText);

        Button closeBtn = new Button(ctx);
        closeBtn.setText("✕");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setBackgroundColor(0x00000000);
        closeBtn.setTextColor(0xFF666666);
        closeBtn.setOnClickListener(v -> hidePanel());
        titleBar.addView(closeBtn);
        mainContainer.addView(titleBar);

        ScrollView contentScroll = new ScrollView(ctx);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout contentLayout = new LinearLayout(ctx);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);

        // ---- 模块1: 定位 ----
        addCollapsibleSection(ctx, contentLayout, "🌏 模拟定位", locationEnabled,
                (buttonView, isChecked) -> {
                    locationEnabled = isChecked;
                    // 实时持久化开关状态
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean("locationEnabled", isChecked).apply();
                },
                (layout) -> addLocationContent(ctx, layout));

        // ---- 模块2: WiFi ----
        addCollapsibleSection(ctx, contentLayout, "📶 WiFi模拟", wifiEnabled,
                (buttonView, isChecked) -> wifiEnabled = isChecked,
                (layout) -> addWiFiContent(ctx, layout));

        // ---- 模块3: 蓝牙 ----
        addCollapsibleSection(ctx, contentLayout, "🔵 蓝牙BLE模拟", bleEnabled,
                (buttonView, isChecked) -> bleEnabled = isChecked,
                (layout) -> addBLEContent(ctx, layout));

        // ---- 模块4: 相机替换 ----
        addCollapsibleSection(ctx, contentLayout, "📷 相机替换", cameraEnabled,
                (buttonView, isChecked) -> cameraEnabled = isChecked,
                (layout) -> addCameraContent(ctx, layout));

        mainContainer.addView(contentScroll);

        Button saveBtn = new Button(ctx);
        saveBtn.setText("💾 保存所有设置");
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setPadding(0, 20, 0, 20);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = 16;
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show();
        });
        mainContainer.addView(saveBtn);

        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
                panelWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.CENTER;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        final int[] panelStartX = new int[1];
        final int[] panelStartY = new int[1];
        final float[] touchStartX = new float[1];
        final float[] touchStartY = new float[1];

        titleBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        panelStartX[0] = panelParams.x;
                        panelStartY[0] = panelParams.y;
                        touchStartX[0] = event.getRawX();
                        touchStartY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = panelStartX[0] + (int) (event.getRawX() - touchStartX[0]);
                        panelParams.y = panelStartY[0] + (int) (event.getRawY() - touchStartY[0]);
                        panelParams.gravity = Gravity.TOP | Gravity.START;
                        if (windowManager != null && panelView != null) {
                            try {
                                windowManager.updateViewLayout(panelView, panelParams);
                            } catch (Throwable t) {
                                Log.e(TAG, "更新面板位置失败", t);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(mainContainer, panelParams);
            panelView = mainContainer;
            isPanelShowing = true;
            Log.e(TAG, "设置面板显示成功");
        } catch (Throwable t) {
            Log.e(TAG, "设置面板显示失败", t);
            showFallbackDialog(ctx);
        }
    }

    // ---- 折叠模块 ----
    private void addCollapsibleSection(Context ctx, LinearLayout parentLayout, String title,
            boolean isChecked,
            CompoundButton.OnCheckedChangeListener toggleListener,
            java.util.function.Consumer<LinearLayout> contentBuilder) {

        final boolean[] checked = {isChecked};

        LinearLayout sectionLayout = new LinearLayout(ctx);
        sectionLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLayout.setLayoutParams(sectionParams);

        LinearLayout headerLayout = new LinearLayout(ctx);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(8, 12, 8, 12);
        headerLayout.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerLayout.setLayoutParams(headerParams);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerLayout.addView(tv);

        TextView statusToggle = new TextView(ctx);
        statusToggle.setText(checked ? "开启" : "关闭");
        statusToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusToggle.setTextColor(checked ? 0xFF4CAF50 : 0xFFF44336);
        statusToggle.setGravity(Gravity.CENTER);
        statusToggle.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusToggle.setLayoutParams(toggleParams);
        headerLayout.addView(statusToggle);

        sectionLayout.addView(headerLayout);

        LinearLayout contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(8, 0, 8, 8);
        contentContainer.setVisibility(View.GONE);

        contentBuilder.accept(contentContainer);

        sectionLayout.addView(contentContainer);

        View divider = new View(ctx);
        divider.setBackgroundColor(0xFFDDDDDD);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divider.setLayoutParams(divParams);
        sectionLayout.addView(divider);

        final boolean[] isExpanded = {false};
        headerLayout.setOnClickListener(v -> {
            isExpanded[0] = !isExpanded[0];
            contentContainer.setVisibility(isExpanded[0] ? View.VISIBLE : View.GONE);
        });

        statusToggle.setOnClickListener(v -> {
            checked = !checked;
            statusToggle.setText(checked ? "开启" : "关闭");
            statusToggle.setTextColor(checked ? 0xFF4CAF50 : 0xFFF44336);
            toggleListener.onCheckedChanged(null, checked);
        });

        parentLayout.addView(sectionLayout);
    }

    // ---- 定位内容（含地图选点） ----
    private void addLocationContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        layout.addView(createLabel(ctx, "经度 (Longitude):"));
        EditText lngEditLocal = createEditText(ctx, customLng, "例如: 121.808512");
        lngEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLng = s;
            // 实时持久化，Hook回调中直接读取最新值
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lng", s).apply();
        }));
        lngEdit = lngEditLocal;
        layout.addView(lngEditLocal);

        layout.addView(createLabel(ctx, "纬度 (Latitude):"));
        EditText latEditLocal = createEditText(ctx, customLat, "例如: 31.141585");
        latEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLat = s;
            // 实时持久化，Hook回调中直接读取最新值
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lat", s).apply();
        }));
        latEdit = latEditLocal;
        layout.addView(latEditLocal);

        addDivider(layout);

        // 地图搜索
        layout.addView(createLabel(ctx, "地图搜索选点:"));
        EditText searchEdit = createEditText(ctx, mapSearchKeyword, "输入地址或地点名称");
        layout.addView(searchEdit);

        Button searchBtn = new Button(ctx);
        searchBtn.setText("🔍 搜索位置");
        searchBtn.setOnClickListener(v -> {
            String keyword = searchEdit.getText().toString().trim();
            if (keyword.isEmpty()) {
                Toast.makeText(ctx, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
                return;
            }
            mapSearchKeyword = keyword;
            performMapSearch(ctx, keyword, layout);
        });
        layout.addView(searchBtn);

        // 搜索结果列表
        if (!mapSearchResults.isEmpty()) {
            TextView resultLabel = createLabel(ctx, "搜索结果 (点击选择):");
            layout.addView(resultLabel);
            for (int i = 0; i < mapSearchResults.size(); i++) {
                try {
                    JSONObject item = mapSearchResults.get(i);
                    String name = item.optString("name", "未知");
                    String address = item.optString("address", "");
                    final double lat = item.optDouble("lat", 0);
                    final double lng = item.optDouble("lng", 0);

                    Button locBtn = new Button(ctx);
                    locBtn.setText(name + "\n" + address);
                    locBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    locBtn.setOnClickListener(v -> {
                        customLat = String.valueOf(lat);
                        customLng = String.valueOf(lng);
                        if (latEdit != null) latEdit.setText(customLat);
                        if (lngEdit != null) lngEdit.setText(customLng);
                        // 实时持久化
                        SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                        ed.putString("lat", customLat);
                        ed.putString("lng", customLng);
                        ed.apply();
                        Toast.makeText(ctx, "已选择: " + name, Toast.LENGTH_SHORT).show();
                    });
                    layout.addView(locBtn);
                } catch (Exception e) {
                    Log.e(TAG, "显示搜索结果失败", e);
                }
            }
        }

        addDivider(layout);

        Button curLocBtn = new Button(ctx);
        curLocBtn.setText("📍 获取当前位置");
        curLocBtn.setOnClickListener(v -> {
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    Toast.makeText(ctx, "无法获取位置服务", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                            && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(ctx, "缺少位置权限", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    customLat = String.valueOf(loc.getLatitude());
                    customLng = String.valueOf(loc.getLongitude());
                    lngEdit.setText(customLng);
                    latEdit.setText(customLat);
                    // 实时持久化
                    SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                    ed.putString("lat", customLat);
                    ed.putString("lng", customLng);
                    ed.apply();
                    Toast.makeText(ctx, "已获取当前位置", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "无法获取当前位置", Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException se) {
                Toast.makeText(ctx, "位置权限被拒绝", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(ctx, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(curLocBtn);
    }

    // ---- 地图搜索实现 ----
    private void performMapSearch(Context ctx, String keyword, LinearLayout layout) {
        new Thread(() -> {
            try {
                // 使用腾讯地图地点搜索API（与插件一致）
                // 请求URL: https://apis.map.qq.com/ws/place/v1/search
                // 参数: keyword=关键词, boundary=region(全国,1), page_size=10, key=API_KEY
                String url = "https://apis.map.qq.com/ws/place/v1/search?" +
                        "keyword=" + URLEncoder.encode(keyword, "UTF-8") +
                        "&boundary=region(全国,1)" +
                        "&page_size=10" +
                        "&page_index=1" +
                        "&output=json" +
                        "&key=YOUR_QQ_MAP_KEY";

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        bos.write(buffer, 0, len);
                    }
                    String response = bos.toString("UTF-8");
                    is.close();

                    JSONObject json = new JSONObject(response);
                    int status = json.optInt("status", -1);
                    if (status == 0) {
                        // 腾讯地图API status=0表示成功
                        JSONArray data = json.optJSONArray("data");
                        mapSearchResults.clear();
                        if (data != null) {
                            for (int i = 0; i < data.length(); i++) {
                                JSONObject item = data.getJSONObject(i);
                                JSONObject location = item.optJSONObject("location");
                                if (location != null) {
                                    JSONObject result = new JSONObject();
                                    result.put("name", item.optString("title", keyword));
                                    result.put("address", item.optString("address", ""));
                                    result.put("lat", location.optDouble("lat", 0));
                                    result.put("lng", location.optDouble("lng", 0));
                                    mapSearchResults.add(result);
                                }
                            }
                        }

                        uiHandler.post(() -> {
                            if (mapSearchResults.isEmpty()) {
                                Toast.makeText(ctx, "未找到相关位置", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(ctx, "找到 " + mapSearchResults.size() + " 个结果", Toast.LENGTH_SHORT).show();
                                hidePanel();
                                uiHandler.postDelayed(() -> showPanel(ctx), 100);
                            }
                        });
                    } else {
                        // 如果API失败，使用内置常用地点
                        Log.e(TAG, "腾讯地图API返回错误: status=" + status + ", msg=" + json.optString("message", ""));
                        useBuiltinLocations(ctx, keyword);
                    }
                } else {
                    Log.e(TAG, "腾讯地图API HTTP错误: " + responseCode);
                    useBuiltinLocations(ctx, keyword);
                }
            } catch (Exception e) {
                Log.e(TAG, "地图搜索失败", e);
                useBuiltinLocations(ctx, keyword);
            }
        }).start();
    }

    private void useBuiltinLocations(Context ctx, String keyword) {
        // 内置常用地点数据库（简化版）
        mapSearchResults.clear();
        try {
            if (keyword.contains("北京") || keyword.contains("beijing")) {
                JSONObject r1 = new JSONObject();
                r1.put("name", "北京市中心");
                r1.put("address", "北京市东城区");
                r1.put("lng", 116.407526);
                r1.put("lat", 39.90403);
                mapSearchResults.add(r1);
            } else if (keyword.contains("上海") || keyword.contains("shanghai")) {
                JSONObject r1 = new JSONObject();
                r1.put("name", "上海市中心");
                r1.put("address", "上海市黄浦区");
                r1.put("lng", 121.473701);
                r1.put("lat", 31.230416);
                mapSearchResults.add(r1);
            } else if (keyword.contains("广州") || keyword.contains("guangzhou")) {
                JSONObject r1 = new JSONObject();
                r1.put("name", "广州市中心");
                r1.put("address", "广州市越秀区");
                r1.put("lng", 113.264434);
                r1.put("lat", 23.129162);
                mapSearchResults.add(r1);
            } else if (keyword.contains("深圳") || keyword.contains("shenzhen")) {
                JSONObject r1 = new JSONObject();
                r1.put("name", "深圳市中心");
                r1.put("address", "深圳市福田区");
                r1.put("lng", 114.057868);
                r1.put("lat", 22.543099);
                mapSearchResults.add(r1);
            } else {
                // 默认返回一些常见地点
                JSONObject r1 = new JSONObject();
                r1.put("name", "默认地点-北京天安门");
                r1.put("address", "北京市东城区东长安街");
                r1.put("lng", 116.397477);
                r1.put("lat", 39.903738);
                mapSearchResults.add(r1);
            }
        } catch (Exception e) {
            Log.e(TAG, "内置地点失败", e);
        }

        uiHandler.post(() -> {
            Toast.makeText(ctx, "使用内置地点数据", Toast.LENGTH_SHORT).show();
            hidePanel();
            uiHandler.postDelayed(() -> showPanel(ctx), 100);
        });
    }

    // ---- WiFi内容 ----
    private void addWiFiContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        Button scanWifiBtn = new Button(ctx);
        scanWifiBtn.setText("📡 扫描当前WiFi");
        scanWifiBtn.setOnClickListener(v -> scanCurrentWifi(ctx, layout));
        layout.addView(scanWifiBtn);

        TextView wifiListLabel = createLabel(ctx, "扫描结果 (点击选择):");
        layout.addView(wifiListLabel);

        ListView wifiListView = new ListView(ctx);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300);
        wifiListView.setLayoutParams(listParams);
        ArrayAdapter<String> wifiAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_list_item_1, scannedWifiList);
        wifiListView.setAdapter(wifiAdapter);
        wifiListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        wifiListView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = scannedWifiList.get(position);
            String[] parts = selected.split("\\|");
            if (parts.length >= 2) {
                customWifiSSID = parts[0].trim();
                customWifiBSSID = parts[1].trim();
                if (wifiSsidEdit != null) wifiSsidEdit.setText(customWifiSSID);
                if (wifiBssidEdit != null) wifiBssidEdit.setText(customWifiBSSID);
                Toast.makeText(ctx, "已选择: " + customWifiSSID, Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(wifiListView);

        addDivider(layout);

        layout.addView(createLabel(ctx, "自定义WiFi名称 (SSID):"));
        wifiSsidEdit = createEditText(ctx, customWifiSSID, "输入WiFi名称");
        wifiSsidEdit.addTextChangedListener(new SimpleTextWatcher(s -> customWifiSSID = s));
        layout.addView(wifiSsidEdit);

        layout.addView(createLabel(ctx, "自定义WiFi MAC (BSSID):"));
        wifiBssidEdit = createEditText(ctx, customWifiBSSID, "例如: AA:BB:CC:DD:EE:FF");
        wifiBssidEdit.addTextChangedListener(new SimpleTextWatcher(s -> customWifiBSSID = s));
        layout.addView(wifiBssidEdit);
    }

    // ---- 蓝牙内容 ----
    private void addBLEContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        Button scanBleBtn = new Button(ctx);
        scanBleBtn.setText("📡 扫描附近BLE设备");
        scanBleBtn.setOnClickListener(v -> scanNearbyBLE(ctx, layout));
        layout.addView(scanBleBtn);

        TextView bleListLabel = createLabel(ctx, "扫描结果 (点击选择):");
        layout.addView(bleListLabel);

        ListView bleListView = new ListView(ctx);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300);
        bleListView.setLayoutParams(listParams);
        ArrayAdapter<String> bleAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_list_item_1, scannedBleList);
        bleListView.setAdapter(bleAdapter);
        bleListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        bleListView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = scannedBleList.get(position);
            String[] parts = selected.split("\\|");
            if (parts.length >= 2) {
                customBleName = parts[0].trim();
                customBleAddress = parts[1].trim();
                if (bleNameEdit != null) bleNameEdit.setText(customBleName);
                if (bleAddrEdit != null) bleAddrEdit.setText(customBleAddress);
                if (scannedBleDataList.size() > position && bleDataEdit != null) {
                    String data = scannedBleDataList.get(position);
                    customBleData = data;
                    bleDataEdit.setText(data);
                }
                Toast.makeText(ctx, "已选择: " + customBleName, Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(bleListView);

        addDivider(layout);

        layout.addView(createLabel(ctx, "自定义蓝牙设备名:"));
        bleNameEdit = createEditText(ctx, customBleName, "输入蓝牙设备名称");
        bleNameEdit.addTextChangedListener(new SimpleTextWatcher(s -> customBleName = s));
        layout.addView(bleNameEdit);

        layout.addView(createLabel(ctx, "自定义蓝牙MAC地址:"));
        bleAddrEdit = createEditText(ctx, customBleAddress, "例如: AA:BB:CC:DD:EE:FF");
        bleAddrEdit.addTextChangedListener(new SimpleTextWatcher(s -> customBleAddress = s));
        layout.addView(bleAddrEdit);

        layout.addView(createLabel(ctx, "自定义广播数据 (hex):"));
        bleDataEdit = createEditText(ctx, customBleData, "例如: 0201060AFF...");
        bleDataEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bleDataEdit.setMinLines(3);
        bleDataEdit.addTextChangedListener(new SimpleTextWatcher(s -> customBleData = s));
        layout.addView(bleDataEdit);
    }

    // ---- 相机替换内容 ----
    private void addCameraContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        // 模式选择
        layout.addView(createLabel(ctx, "替换模式:"));
        RadioGroup modeGroup = new RadioGroup(ctx);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);

        RadioButton singleMode = new RadioButton(ctx);
        singleMode.setText("单张替换");
        singleMode.setId(View.generateViewId());
        singleMode.setChecked(cameraMode == 0);

        RadioButton multiMode = new RadioButton(ctx);
        multiMode.setText("多张循环");
        multiMode.setId(View.generateViewId());
        multiMode.setChecked(cameraMode == 1);

        modeGroup.addView(singleMode);
        modeGroup.addView(multiMode);
        layout.addView(modeGroup);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == singleMode.getId()) {
                cameraMode = 0;
            } else if (checkedId == multiMode.getId()) {
                cameraMode = 1;
            }
            hidePanel();
            uiHandler.postDelayed(() -> showPanel(ctx), 100);
        });

        addDivider(layout);

        if (cameraMode == 0) {
            // 单张模式
            layout.addView(createLabel(ctx, "单张图片替换:"));

            Button selectSingleBtn = new Button(ctx);
            selectSingleBtn.setText("🖼 选择图片");
            selectSingleBtn.setOnClickListener(v -> openImagePicker(ctx, true));
            layout.addView(selectSingleBtn);

            if (!singleImagePath.isEmpty()) {
                TextView pathText = createLabel(ctx, "已选: " + new File(singleImagePath).getName());
                layout.addView(pathText);

                ImageView preview = new ImageView(ctx);
                preview.setLayoutParams(new LinearLayout.LayoutParams(200, 200));
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(singleImagePath);
                    if (bmp != null) {
                        preview.setImageBitmap(bmp);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "预览图片失败", e);
                }
                layout.addView(preview);
            }
        } else {
            // 多张循环模式
            layout.addView(createLabel(ctx, "多张图片循环替换:"));

            Button selectMultiBtn = new Button(ctx);
            selectMultiBtn.setText("🖼 选择多张图片");
            selectMultiBtn.setOnClickListener(v -> openImagePicker(ctx, false));
            layout.addView(selectMultiBtn);

            if (!multiImagePaths.isEmpty()) {
                TextView countText = createLabel(ctx, "已选 " + multiImagePaths.size() + " 张图片");
                layout.addView(countText);

                imagePreviewContainer = new LinearLayout(ctx);
                imagePreviewContainer.setOrientation(LinearLayout.HORIZONTAL);
                refreshImagePreviews(ctx);
                layout.addView(imagePreviewContainer);

                imageStatusText = createLabel(ctx, "当前使用: 第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张");
                layout.addView(imageStatusText);

                // 拖拽提示
                TextView dragHint = createLabel(ctx, "提示: 长按图片可拖拽调整顺序");
                dragHint.setTextColor(0xFF666666);
                dragHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                layout.addView(dragHint);
            }
        }
    }

    private void refreshImagePreviews(Context ctx) {
        if (imagePreviewContainer == null) return;
        imagePreviewContainer.removeAllViews();

        for (int i = 0; i < multiImagePaths.size(); i++) {
            final int index = i;
            String path = multiImagePaths.get(i);

            LinearLayout itemLayout = new LinearLayout(ctx);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setPadding(4, 4, 4, 4);

            ImageView imgView = new ImageView(ctx);
            LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(120, 120);
            imgView.setLayoutParams(imgParams);
            imgView.setBackgroundColor(0xFFEEEEEE);

            try {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    imgView.setImageBitmap(bmp);
                }
            } catch (Exception e) {
                Log.e(TAG, "加载缩略图失败", e);
            }

            // 当前使用的高亮边框
            if (i == currentImageIndex) {
                imgView.setPadding(4, 4, 4, 4);
                imgView.setBackgroundColor(0xFF4CAF50);
            }

            // 长按拖拽排序
            imgView.setOnLongClickListener(v -> {
                if (index > 0) {
                    // 简单交换：与上一个交换
                    Collections.swap(multiImagePaths, index, index - 1);
                    if (currentImageIndex == index) currentImageIndex = index - 1;
                    else if (currentImageIndex == index - 1) currentImageIndex = index;
                    refreshImagePreviews(ctx);
                    updateImageStatus();
                    Toast.makeText(ctx, "已调整顺序", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            // 点击切换当前使用
            imgView.setOnClickListener(v -> {
                currentImageIndex = index;
                refreshImagePreviews(ctx);
                updateImageStatus();
                Toast.makeText(ctx, "已切换到第 " + (currentImageIndex + 1) + " 张", Toast.LENGTH_SHORT).show();
            });

            itemLayout.addView(imgView);

            TextView numText = new TextView(ctx);
            numText.setText(String.valueOf(i + 1));
            numText.setGravity(Gravity.CENTER);
            numText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            itemLayout.addView(numText);

            imagePreviewContainer.addView(itemLayout);
        }
    }

    private void updateImageStatus() {
        if (imageStatusText != null && !multiImagePaths.isEmpty()) {
            imageStatusText.setText("当前使用: 第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张");
        }
    }

    private void openImagePicker(Context ctx, boolean single) {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            if (!single) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 由于无法直接启动Activity获取结果，使用文件浏览器方式
            Toast.makeText(ctx, "请将图片复制到 /sdcard/HookImages/ 目录", Toast.LENGTH_LONG).show();

            // 尝试从固定目录加载
            File imgDir = new File("/sdcard/HookImages/");
            if (imgDir.exists() && imgDir.isDirectory()) {
                File[] files = imgDir.listFiles();
                if (files != null) {
                    if (single) {
                        for (File f : files) {
                            if (f.getName().toLowerCase().endsWith(".jpg") ||
                                    f.getName().toLowerCase().endsWith(".jpeg") ||
                                    f.getName().toLowerCase().endsWith(".png")) {
                                singleImagePath = f.getAbsolutePath();
                                break;
                            }
                        }
                    } else {
                        multiImagePaths.clear();
                        for (File f : files) {
                            if (f.getName().toLowerCase().endsWith(".jpg") ||
                                    f.getName().toLowerCase().endsWith(".jpeg") ||
                                    f.getName().toLowerCase().endsWith(".png")) {
                                multiImagePaths.add(f.getAbsolutePath());
                            }
                        }
                        currentImageIndex = 0;
                    }
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                    Toast.makeText(ctx, "已加载图片", Toast.LENGTH_SHORT).show();
                }
            } else {
                imgDir.mkdirs();
                Toast.makeText(ctx, "已创建目录 /sdcard/HookImages/，请放入图片", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.e(TAG, "打开图片选择失败", t);
            Toast.makeText(ctx, "打开图片选择失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ======================== WiFi扫描 ========================

    private void scanCurrentWifi(Context ctx, LinearLayout layout) {
        try {
            WifiManager wifiManager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                Toast.makeText(ctx, "无法获取WifiManager", Toast.LENGTH_SHORT).show();
                return;
            }
            scannedWifiList.clear();
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                String ssid = wifiInfo.getSSID();
                String bssid = wifiInfo.getBSSID();
                if (ssid != null) {
                    ssid = ssid.replace("\"", "");
                    scannedWifiList.add("当前连接: " + ssid + " | " + bssid);
                }
            }
            List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
            if (results != null) {
                for (android.net.wifi.ScanResult r : results) {
                    String ssid = r.SSID;
                    String bssid = r.BSSID;
                    if (ssid != null && !ssid.isEmpty()) {
                        scannedWifiList.add(ssid + " | " + bssid + " | " + r.level + "dBm");
                    }
                }
            }
            if (scannedWifiList.isEmpty()) {
                scannedWifiList.add("未发现WiFi，请确保WiFi已开启");
            }
            Toast.makeText(ctx, "扫描到 " + scannedWifiList.size() + " 个WiFi", Toast.LENGTH_SHORT).show();
            hidePanel();
            uiHandler.postDelayed(() -> showPanel(ctx), 100);
        } catch (Throwable t) {
            Log.e(TAG, "扫描WiFi失败", t);
            Toast.makeText(ctx, "扫描WiFi失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ======================== BLE扫描 ========================

    private void scanNearbyBLE(Context ctx, LinearLayout layout) {
        try {
            BluetoothManager btManager = (BluetoothManager) ctx.getApplicationContext()
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            if (btManager == null) {
                Toast.makeText(ctx, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }
            BluetoothAdapter btAdapter = btManager.getAdapter();
            if (btAdapter == null || !btAdapter.isEnabled()) {
                Toast.makeText(ctx, "蓝牙未开启", Toast.LENGTH_SHORT).show();
                return;
            }
            scannedBleList.clear();
            scannedBleDataList.clear();
            Toast.makeText(ctx, "开始扫描BLE设备...", Toast.LENGTH_SHORT).show();
            BluetoothLeScanner scanner = btAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                Toast.makeText(ctx, "无法获取BLE扫描器", Toast.LENGTH_SHORT).show();
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            final ScanCallback[] scanCallbackHolder = new ScanCallback[1];
            scanCallbackHolder[0] = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    if (device != null) {
                        String name = device.getName();
                        if (name == null || name.isEmpty()) name = "未知设备";
                        String addr = device.getAddress();
                        int rssi = result.getRssi();
                        String entry = name + " | " + addr + " | " + rssi + "dBm";
                        String broadcastData = "";
                        try {
                            android.bluetooth.le.ScanRecord record = result.getScanRecord();
                            if (record != null) {
                                byte[] bytes = record.getBytes();
                                if (bytes != null && bytes.length > 0) {
                                    StringBuilder sb = new StringBuilder();
                                    for (byte b : bytes) {
                                        sb.append(String.format("%02X", b & 0xFF));
                                    }
                                    broadcastData = sb.toString();
                                }
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "提取广播数据失败", t);
                        }
                        boolean exists = false;
                        for (int i = 0; i < scannedBleList.size(); i++) {
                            if (scannedBleList.get(i).contains(addr)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            scannedBleList.add(entry);
                            scannedBleDataList.add(broadcastData);
                            Log.e(TAG, "发现BLE: " + entry + " data=" + broadcastData);
                        }
                    }
                }
                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "BLE扫描失败: " + errorCode);
                    uiHandler.post(() -> Toast.makeText(ctx, "BLE扫描失败: " + errorCode, Toast.LENGTH_SHORT).show());
                }
            };
            scanner.startScan(Collections.emptyList(), settings, scanCallbackHolder[0]);
            uiHandler.postDelayed(() -> {
                try {
                    scanner.stopScan(scanCallbackHolder[0]);
                } catch (Throwable t) {}
                Log.e(TAG, "BLE扫描完成，发现 " + scannedBleList.size() + " 个设备");
                uiHandler.post(() -> {
                    Toast.makeText(ctx, "扫描到 " + scannedBleList.size() + " 个BLE设备", Toast.LENGTH_SHORT).show();
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                });
            }, 10000);
        } catch (Throwable t) {
            Log.e(TAG, "扫描BLE失败", t);
            Toast.makeText(ctx, "扫描BLE失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ======================== Hook 高德定位 ========================

    private void hookAMapLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = appContext.getClassLoader();
            Class<?> aMapLocationClass = cl.loadClass("com.amap.api.location.AMapLocation");
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 每次调用实时读取配置，无需重启即可生效
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String latStr = sh.getString("lat", "");
                    if (latStr.isEmpty()) return;
                    try {
                        double lat = Double.parseDouble(latStr);
                        Log.e(TAG, "Hook getLatitude: " + lat);
                        param.setResult(lat);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "经度格式错误", e);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 每次调用实时读取配置，无需重启即可生效
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String lngStr = sh.getString("lng", "");
                    if (lngStr.isEmpty()) return;
                    try {
                        double lng = Double.parseDouble(lngStr);
                        Log.e(TAG, "Hook getLongitude: " + lng);
                        param.setResult(lng);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "纬度格式错误", e);
                    }
                }
            });
            Log.e(TAG, "高德定位Hook成功（实时配置模式）");
        } catch (Throwable t) {
            Log.e(TAG, "高德定位Hook失败", t);
        }
    }

    // ======================== Hook WiFi ========================

    private void hookDingTalkWiFi(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        try {
            XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (wifiEnabled && !customWifiSSID.isEmpty()) {
                        List<android.net.wifi.ScanResult> originalResults = (List<android.net.wifi.ScanResult>) param.getResult();
                        if (originalResults == null) originalResults = new ArrayList<>();
                        boolean found = false;
                        for (android.net.wifi.ScanResult r : originalResults) {
                            if (customWifiSSID.equals(r.SSID) ||
                                    (customWifiBSSID != null && !customWifiBSSID.isEmpty() && customWifiBSSID.equals(r.BSSID))) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            try {
                                android.net.wifi.ScanResult fakeResult = createFakeScanResult(customWifiSSID, customWifiBSSID);
                                if (fakeResult != null) {
                                    originalResults.add(0, fakeResult);
                                    param.setResult(originalResults);
                                    Log.e(TAG, "已注入自定义WiFi: " + customWifiSSID);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "创建假ScanResult失败", t);
                            }
                        }
                    }
                }
            });
            Log.e(TAG, "WifiManager.getScanResults Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "WifiManager.getScanResults Hook失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (wifiEnabled && !customWifiSSID.isEmpty()) {
                        WifiInfo info = (WifiInfo) param.getResult();
                        if (info != null) {
                            try {
                                setWifiInfoField(info, "mSSID", "\"" + customWifiSSID + "\"");
                                if (!customWifiBSSID.isEmpty()) {
                                    setWifiInfoField(info, "mBSSID", customWifiBSSID);
                                }
                                Log.e(TAG, "已修改WiFi连接信息: " + customWifiSSID);
                            } catch (Throwable t) {
                                Log.e(TAG, "修改WifiInfo失败", t);
                            }
                        }
                    }
                }
            });
            Log.e(TAG, "WifiManager.getConnectionInfo Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "WifiManager.getConnectionInfo Hook失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (wifiEnabled && !customWifiSSID.isEmpty()) {
                        param.setResult("\"" + customWifiSSID + "\"");
                        Log.e(TAG, "Hook WifiInfo.getSSID: " + customWifiSSID);
                    }
                }
            });
            Log.e(TAG, "WifiInfo.getSSID Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "WifiInfo.getSSID Hook失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getBSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (wifiEnabled && !customWifiBSSID.isEmpty()) {
                        param.setResult(customWifiBSSID);
                        Log.e(TAG, "Hook WifiInfo.getBSSID: " + customWifiBSSID);
                    }
                }
            });
            Log.e(TAG, "WifiInfo.getBSSID Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "WifiInfo.getBSSID Hook失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getMacAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (wifiEnabled && !customWifiBSSID.isEmpty()) {
                        param.setResult(customWifiBSSID);
                        Log.e(TAG, "Hook WifiInfo.getMacAddress: " + customWifiBSSID);
                    }
                }
            });
            Log.e(TAG, "WifiInfo.getMacAddress Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "WifiInfo.getMacAddress Hook失败", t);
        }

        hookDingTalkAttendanceWiFi(cl);
    }

    private void hookDingTalkAttendanceWiFi(ClassLoader cl) {
        String[] possibleWifiClasses = {
                "com.alibaba.android.rimet.biz.checkin.model.WifiCheckInInfo",
                "com.alibaba.android.rimet.biz.checkin.wifi.WifiCheckManager",
                "com.alibaba.android.rimet.biz.checkin.wifi.WifiScanner",
                "com.alibaba.android.rimet.biz.checkin.location.CheckInWifiInfo",
                "com.dingtalk.checkin.wifi.WifiInfoModel",
                "com.alibaba.android.rimet.biz.checkin.wifi.WifiInfoHelper",
                "com.alibaba.android.rimet.biz.checkin.CheckInWifiHelper",
                "com.alibaba.android.rimet.biz.checkin.wifialgorithm.WifiCheckModel",
        };
        for (String className : possibleWifiClasses) {
            try {
                Class<?> wifiClass = cl.loadClass(className);
                Log.e(TAG, "找到xxx应用名WiFi类: " + className);
                hookGetSSIDMethods(wifiClass);
                hookGetBSSIDMethods(wifiClass);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "未找到WiFi类: " + className);
            } catch (Throwable t) {
                Log.e(TAG, "Hook WiFi类失败: " + className, t);
            }
        }
    }

    private void hookGetSSIDMethods(Class<?> wifiClass) {
        try {
            String[] ssidMethods = {"getSSID", "getSsid", "getWifiSSID", "getSsidName"};
            for (String method : ssidMethods) {
                try {
                    XposedHelpers.findAndHookMethod(wifiClass, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (wifiEnabled && !customWifiSSID.isEmpty()) {
                                param.setResult(customWifiSSID);
                                Log.e(TAG, "Hook WiFi SSID: " + customWifiSSID);
                            }
                        }
                    });
                    Log.e(TAG, "Hook " + method + " 成功 in " + wifiClass.getName());
                    break;
                } catch (NoSuchMethodError e) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook SSID方法失败", t);
        }
    }

    private void hookGetBSSIDMethods(Class<?> wifiClass) {
        try {
            String[] bssidMethods = {"getBSSID", "getBssid", "getWifiBSSID", "getMacAddress"};
            for (String method : bssidMethods) {
                try {
                    XposedHelpers.findAndHookMethod(wifiClass, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (wifiEnabled && !customWifiBSSID.isEmpty()) {
                                param.setResult(customWifiBSSID);
                                Log.e(TAG, "Hook WiFi BSSID: " + customWifiBSSID);
                            }
                        }
                    });
                    Log.e(TAG, "Hook " + method + " 成功 in " + wifiClass.getName());
                    break;
                } catch (NoSuchMethodError e) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook BSSID方法失败", t);
        }
    }

    // ======================== Hook 蓝牙 ========================

    private void hookDingTalkBluetooth(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        try {
            hookBluetoothLeScan();
            Log.e(TAG, "BLE扫描Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "BLE扫描Hook失败", t);
        }
        hookDingTalkAttendanceBLE(cl);
    }

    private void hookBluetoothLeScan() {
        try {
            XposedHelpers.findAndHookMethod(BluetoothLeScanner.class, "startScan",
                    List.class, ScanSettings.class, ScanCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wrapBleScanCallback(param, 2);
                }
            });
            Log.e(TAG, "Hook startScan(List,ScanSettings,ScanCallback) 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook startScan(List,ScanSettings,ScanCallback) 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(BluetoothLeScanner.class, "startScan",
                    List.class, ScanSettings.class, ScanCallback.class, android.os.Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wrapBleScanCallback(param, 2);
                }
            });
            Log.e(TAG, "Hook startScan(List,ScanSettings,ScanCallback,Handler) 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook startScan(List,ScanSettings,ScanCallback,Handler) 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(BluetoothLeScanner.class, "startScan",
                    ScanCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wrapBleScanCallback(param, 0);
                }
            });
            Log.e(TAG, "Hook startScan(ScanCallback) 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook startScan(ScanCallback) 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(BluetoothLeScanner.class, "startScan",
                    List.class, ScanCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    wrapBleScanCallback(param, 1);
                }
            });
            Log.e(TAG, "Hook startScan(List,ScanCallback) 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook startScan(List,ScanCallback) 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(BluetoothDevice.class, "getName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (bleEnabled && !customBleName.isEmpty()) {
                        String originalName = (String) param.getResult();
                        if (originalName == null || originalName.isEmpty()
                                || (customBleAddress != null && !customBleAddress.isEmpty()
                                    && customBleAddress.equals(((BluetoothDevice) param.thisObject).getAddress()))) {
                            param.setResult(customBleName);
                            Log.e(TAG, "Hook BLE getName: " + customBleName);
                        }
                    }
                }
            });
            Log.e(TAG, "BluetoothDevice.getName Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "BluetoothDevice.getName Hook失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(BluetoothDevice.class, "getAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (bleEnabled && !customBleAddress.isEmpty()) {
                        param.setResult(customBleAddress);
                        Log.e(TAG, "Hook BLE getAddress: " + customBleAddress);
                    }
                }
            });
            Log.e(TAG, "BluetoothDevice.getAddress Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "BluetoothDevice.getAddress Hook失败", t);
        }
    }

    private void wrapBleScanCallback(XC_MethodHook.MethodHookParam param, int callbackIndex) {
        if (!bleEnabled || customBleName.isEmpty()) return;
        try {
            final ScanCallback originalCallback = (ScanCallback) param.args[callbackIndex];
            final ScanCallback wrapperCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                    try {
                        originalCallback.onScanResult(callbackType, result);
                    } catch (Throwable t) {
                        Log.e(TAG, "传递BLE扫描结果失败", t);
                    }
                }
                @Override
                public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results) {
                    try {
                        originalCallback.onBatchScanResults(results);
                    } catch (Throwable t) {
                        Log.e(TAG, "传递BLE批量结果失败", t);
                    }
                }
                @Override
                public void onScanFailed(int errorCode) {
                    try {
                        originalCallback.onScanFailed(errorCode);
                    } catch (Throwable t) {
                        Log.e(TAG, "传递BLE扫描失败", t);
                    }
                }
            };
            param.args[callbackIndex] = wrapperCallback;
            Handler injectHandler = new Handler(Looper.getMainLooper());
            injectHandler.postDelayed(() -> {
                try {
                    injectCustomBLEDevice(originalCallback);
                } catch (Throwable t) {
                    Log.e(TAG, "注入BLE设备失败", t);
                }
            }, 2000);
        } catch (Throwable t) {
            Log.e(TAG, "包装BLE回调失败", t);
        }
    }

    private void injectCustomBLEDevice(ScanCallback callback) {
        if (!bleEnabled || customBleName.isEmpty()) return;
        try {
            BluetoothDevice mockDevice = createMockBluetoothDevice(customBleName, customBleAddress);
            if (mockDevice != null) {
                android.bluetooth.le.ScanResult mockResult = createFakeBleScanResult(mockDevice);
                if (mockResult != null) {
                    callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, mockResult);
                    Log.e(TAG, "已注入自定义BLE设备: " + customBleName);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "注入BLE设备失败", t);
        }
    }

    private void hookDingTalkAttendanceBLE(ClassLoader cl) {
        String[] possibleBleClasses = {
                "com.alibaba.android.rimet.biz.checkin.ble.BleCheckManager",
                "com.alibaba.android.rimet.biz.checkin.ble.BleScanner",
                "com.alibaba.android.rimet.biz.checkin.ble.BleDeviceInfo",
                "com.alibaba.android.rimet.biz.checkin.model.BleCheckInInfo",
                "com.dingtalk.checkin.ble.BleInfoModel",
                "com.alibaba.android.rimet.biz.checkin.ble.BleCheckHelper",
                "com.alibaba.android.rimet.biz.checkin.bluetooth.BleCheckModel",
                "com.alibaba.android.rimet.biz.checkin.ble.BleBeaconInfo",
                "com.alibaba.android.rimet.biz.checkin.ble.IBeaconDetector",
        };
        for (String className : possibleBleClasses) {
            try {
                Class<?> bleClass = cl.loadClass(className);
                Log.e(TAG, "找到xxx应用名BLE类: " + className);
                hookGetBleNameMethods(bleClass);
                hookGetBleAddressMethods(bleClass);
                hookGetBleDataMethods(bleClass);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "未找到BLE类: " + className);
            } catch (Throwable t) {
                Log.e(TAG, "Hook BLE类失败: " + className, t);
            }
        }
    }

    private void hookGetBleNameMethods(Class<?> bleClass) {
        String[] nameMethods = {"getName", "getDeviceName", "getBleName", "getBluetoothName"};
        for (String method : nameMethods) {
            try {
                XposedHelpers.findAndHookMethod(bleClass, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (bleEnabled && !customBleName.isEmpty()) {
                            param.setResult(customBleName);
                            Log.e(TAG, "Hook BLE Name: " + customBleName);
                        }
                    }
                });
                Log.e(TAG, "Hook " + method + " 成功 in " + bleClass.getName());
                break;
            } catch (NoSuchMethodError e) {}
        }
    }

    private void hookGetBleAddressMethods(Class<?> bleClass) {
        String[] addrMethods = {"getAddress", "getMacAddress", "getBleAddress", "getDeviceAddress"};
        for (String method : addrMethods) {
            try {
                XposedHelpers.findAndHookMethod(bleClass, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (bleEnabled && !customBleAddress.isEmpty()) {
                            param.setResult(customBleAddress);
                            Log.e(TAG, "Hook BLE Address: " + customBleAddress);
                        }
                    }
                });
                Log.e(TAG, "Hook " + method + " 成功 in " + bleClass.getName());
                break;
            } catch (NoSuchMethodError e) {}
        }
    }

    private void hookGetBleDataMethods(Class<?> bleClass) {
        String[] dataMethods = {"getRecordData", "getBroadcastData",
                "getManufacturerData", "getServiceData", "getRawData"};
        for (String method : dataMethods) {
            try {
                XposedHelpers.findAndHookMethod(bleClass, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (bleEnabled && !customBleData.isEmpty()) {
                            try {
                                byte[] data = hexStringToByteArray(customBleData);
                                param.setResult(data);
                                Log.e(TAG, "Hook BLE Data: " + customBleData);
                            } catch (Exception e) {
                                Log.e(TAG, "广播数据格式错误", e);
                            }
                        }
                    }
                });
                Log.e(TAG, "Hook " + method + " 成功 in " + bleClass.getName());
                break;
            } catch (NoSuchMethodError e) {}
        }
        hookGetScanRecord(bleClass);
        hookGetBytes(bleClass);
    }

    private void hookGetScanRecord(Class<?> bleClass) {
        try {
            XposedHelpers.findAndHookMethod(bleClass, "getScanRecord", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (bleEnabled && !customBleData.isEmpty()) {
                        try {
                            byte[] data = hexStringToByteArray(customBleData);
                            Class<?> scanRecordClass = Class.forName("android.bluetooth.le.ScanRecord");
                            Object scanRecord = null;
                            try {
                                scanRecord = XposedHelpers.callStaticMethod(scanRecordClass, "parseFromBytes", data);
                            } catch (Throwable t) {
                                Log.e(TAG, "parseFromBytes失败", t);
                            }
                            if (scanRecord == null) {
                                try {
                                    java.lang.reflect.Constructor<?> ctor = scanRecordClass.getDeclaredConstructor(
                                            byte[].class, int.class, int.class);
                                    ctor.setAccessible(true);
                                    scanRecord = ctor.newInstance(data, 0, data.length);
                                } catch (Throwable t) {
                                    Log.e(TAG, "反射构造ScanRecord失败", t);
                                }
                            }
                            if (scanRecord != null) {
                                param.setResult(scanRecord);
                                Log.e(TAG, "Hook BLE ScanRecord: " + customBleData);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "ScanRecord格式错误", e);
                        }
                    }
                }
            });
            Log.e(TAG, "Hook getScanRecord 成功 in " + bleClass.getName());
        } catch (NoSuchMethodError e) {}
    }

    private void hookGetBytes(Class<?> bleClass) {
        try {
            XposedHelpers.findAndHookMethod(bleClass, "getBytes", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (bleEnabled && !customBleData.isEmpty()) {
                        try {
                            byte[] data = hexStringToByteArray(customBleData);
                            param.setResult(data);
                            Log.e(TAG, "Hook BLE getBytes: " + customBleData);
                        } catch (Exception e) {
                            Log.e(TAG, "getBytes格式错误", e);
                        }
                    }
                }
            });
            Log.e(TAG, "Hook getBytes 成功 in " + bleClass.getName());
        } catch (NoSuchMethodError e) {}
    }

    // ======================== Hook 相机 ========================

    private void hookCamera(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!cameraEnabled) return;

        // 优先尝试 Hook 钉钉内部拍照回调（精准Hook）
        try {
            Log.e(TAG, "【PicHook】开始加载精准照片Hook...");
            ClassLoader cl = lpparam.classLoader;

            // Hook CameraActivity2$3.onTakePicture(Bitmap)
            try {
                Class<?> cameraActivity2Class = cl.loadClass("com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2$3");
                XposedHelpers.findAndHookMethod(cameraActivity2Class, "onTakePicture",
                        Bitmap.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (cameraEnabled) {
                            Bitmap fakeBitmap = getFakeBitmap();
                            if (fakeBitmap != null) {
                                param.args[0] = fakeBitmap;
                                Log.e(TAG, "【PicHook】CameraActivity2.onTakePicture 已替换Bitmap");
                            }
                        }
                    }
                });
                Log.e(TAG, "【PicHook】CameraActivity2$3.onTakePicture Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "【PicHook】CameraActivity2$3 Hook失败: " + t.getMessage());
            }

            // Hook CameraActivity3.onTakePicture(Bitmap)
            try {
                Class<?> cameraActivity3Class = cl.loadClass("com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3");
                XposedHelpers.findAndHookMethod(cameraActivity3Class, "onTakePicture",
                        Bitmap.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (cameraEnabled) {
                            Bitmap fakeBitmap = getFakeBitmap();
                            if (fakeBitmap != null) {
                                param.args[0] = fakeBitmap;
                                Log.e(TAG, "【PicHook】CameraActivity3.onTakePicture 已替换Bitmap");
                            }
                        }
                    }
                });
                Log.e(TAG, "【PicHook】CameraActivity3.onTakePicture Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "【PicHook】CameraActivity3 Hook失败: " + t.getMessage());
            }

            // Hook 动态配置类（从SharedPreferences读取）
            try {
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String receiveClassName = prefs.getString("ReceiveClass", "");
                if (!receiveClassName.isEmpty()) {
                    Class<?> receiveClass = cl.loadClass(receiveClassName);
                    XposedHelpers.findAndHookMethod(receiveClass, "a",
                            Context.class, byte[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (cameraEnabled) {
                                byte[] fakeData = getFakeImageDataWithExif();
                                if (fakeData != null) {
                                    param.args[1] = fakeData;
                                    Log.e(TAG, "【PicHook】ReceiveClass.a 已替换byte[]");
                                }
                            }
                        }
                    });
                    Log.e(TAG, "【PicHook】ReceiveClass: " + receiveClassName + " Hook成功");
                }
            } catch (Throwable t) {
                Log.e(TAG, "【PicHook】ReceiveClass Hook失败: " + t.getMessage());
            }

        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】精准照片Hook加载失败", t);
        }

        // 兜底：Hook Camera.takePicture (Camera1 API)
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "takePicture", "android.hardware.Camera.ShutterCallback",
                    "android.hardware.Camera.PictureCallback",
                    "android.hardware.Camera.PictureCallback",
                    "android.hardware.Camera.PictureCallback", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (cameraEnabled) {
                        Object jpegCallback = param.args[3];
                        if (jpegCallback != null) {
                            param.args[3] = createFakePictureCallback(jpegCallback);
                        }
                    }
                }
            });
            Log.e(TAG, "Camera.takePicture Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Camera.takePicture Hook失败", t);
        }

        // 兜底：Hook MediaStore 保存替换
        try {
            XposedHelpers.findAndHookMethod(MediaStore.Images.Media.class, "insertImage",
                    android.content.ContentResolver.class, String.class, String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (cameraEnabled) {
                        String imagePath = (String) param.args[1];
                        replaceSavedImage(imagePath);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "MediaStore Hook失败", t);
        }
    }

    /**
     * 获取假图片Bitmap（支持多张循环 + EXIF旋转校正）
     */
    private Bitmap getFakeBitmap() {
        try {
            String path = null;
            if (cameraMode == 0) {
                path = singleImagePath;
            } else {
                if (!multiImagePaths.isEmpty() && currentImageIndex < multiImagePaths.size()) {
                    path = multiImagePaths.get(currentImageIndex);
                }
            }
            if (path == null || path.isEmpty()) return null;

            File file = new File(path);
            if (!file.exists()) return null;

            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) return null;

            // EXIF旋转校正
            int rotation = getExifRotation(path);
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            Log.e(TAG, "【PicHook】已加载假图片Bitmap: " + path);

            // 切换到下一张（多张循环模式）
            if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
                currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
                uiHandler.post(() -> {
                    updateImageStatus();
                    if (imagePreviewContainer != null) {
                        refreshImagePreviews(appContext);
                    }
                    Toast.makeText(appContext,
                            "已切换到第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张",
                            Toast.LENGTH_SHORT).show();
                });
            }

            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】获取假图片Bitmap失败", t);
        }
        return null;
    }

    /**
     * 获取EXIF旋转角度
     */
    private int getExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】读取EXIF失败", t);
        }
        return 0;
    }

    /**
     * 获取假图片byte[]数据（带EXIF旋转校正，用于ReceiveClass.a的byte[]替换）
     */
    private byte[] getFakeImageDataWithExif() {
        try {
            Bitmap fakeBitmap = getFakeBitmap();
            if (fakeBitmap == null) return null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            fakeBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】获取假图片byte[]失败", t);
        }
        return null;
    }

    private Object createFakePictureCallback(Object originalCallback) {
        try {
            Class<?> pictureCallbackClass = Class.forName("android.hardware.Camera$PictureCallback");
            return java.lang.reflect.Proxy.newProxyInstance(
                    originalCallback.getClass().getClassLoader(),
                    new Class<?>[]{pictureCallbackClass},
                    (proxy, method, args) -> {
                        if ("onPictureTaken".equals(method.getName()) && args.length > 0) {
                            byte[] fakeData = getFakeImageData();
                            if (fakeData != null) {
                                args[0] = fakeData;
                                if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
                                    currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
                                    uiHandler.post(() -> {
                                        updateImageStatus();
                                        if (imagePreviewContainer != null) {
                                            refreshImagePreviews(appContext);
                                        }
                                        Toast.makeText(appContext,
                                                "已切换到第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张",
                                                Toast.LENGTH_SHORT).show();
                                    });
                                }
                            }
                        }
                        return method.invoke(originalCallback, args);
                    });
        } catch (Throwable t) {
            Log.e(TAG, "创建假PictureCallback失败", t);
            return originalCallback;
        }
    }

    private byte[] getFakeImageData() {
        try {
            String path = null;
            if (cameraMode == 0) {
                path = singleImagePath;
            } else {
                if (!multiImagePaths.isEmpty() && currentImageIndex < multiImagePaths.size()) {
                    path = multiImagePaths.get(currentImageIndex);
                }
            }
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                if (file.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        bos.write(buffer, 0, len);
                    }
                    fis.close();
                    Log.e(TAG, "已加载假图片: " + path);
                    return bos.toByteArray();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "获取假图片数据失败", t);
        }
        return null;
    }

    private void replaceSavedImage(String originalPath) {
        try {
            byte[] fakeData = getFakeImageData();
            if (fakeData != null && originalPath != null) {
                FileOutputStream fos = new FileOutputStream(originalPath);
                fos.write(fakeData);
                fos.close();
                Log.e(TAG, "已替换保存的图片: " + originalPath);

                if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
                    currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
                    uiHandler.post(() -> {
                        updateImageStatus();
                        if (imagePreviewContainer != null && appContext != null) {
                            refreshImagePreviews(appContext);
                        }
                        Toast.makeText(appContext,
                                "已切换到第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "替换保存图片失败", t);
        }
    }

    // ======================== 辅助方法 ========================

    private android.net.wifi.ScanResult createFakeScanResult(String ssid, String bssid) {
        try {
            android.net.wifi.ScanResult result = null;
            try {
                result = (android.net.wifi.ScanResult) XposedHelpers.newInstance(android.net.wifi.ScanResult.class);
            } catch (Throwable newInstError) {
                try {
                    java.lang.reflect.Constructor<?> ctor = android.net.wifi.ScanResult.class.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    result = (android.net.wifi.ScanResult) ctor.newInstance();
                } catch (Throwable ctorError) {
                    Log.e(TAG, "ScanResult构造失败", ctorError);
                    return null;
                }
            }
            setScanResultField(result, "SSID", ssid);
            setScanResultField(result, "BSSID", bssid);
            setScanResultField(result, "level", -50);
            setScanResultField(result, "frequency", 2412);
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "创建ScanResult失败", t);
            return null;
        }
    }

    private void setScanResultField(Object obj, String fieldName, Object value) {
        try {
            Field field = android.net.wifi.ScanResult.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (NoSuchFieldException e) {
            try {
                Field[] fields = android.net.wifi.ScanResult.class.getDeclaredFields();
                for (Field f : fields) {
                    if (f.getName().equalsIgnoreCase(fieldName)) {
                        f.setAccessible(true);
                        f.set(obj, value);
                        return;
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "设置ScanResult字段失败: " + fieldName, ex);
            }
        } catch (Throwable t) {
            Log.e(TAG, "设置ScanResult字段失败: " + fieldName, t);
        }
    }

    private void setWifiInfoField(Object obj, String fieldName, String value) {
        try {
            Field field = WifiInfo.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (NoSuchFieldException e) {
            try {
                Field[] fields = WifiInfo.class.getDeclaredFields();
                for (Field f : fields) {
                    if (f.getName().equalsIgnoreCase(fieldName)) {
                        f.setAccessible(true);
                        f.set(obj, value);
                        return;
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "设置WifiInfo字段失败: " + fieldName, ex);
            }
        } catch (Throwable t) {
            Log.e(TAG, "设置WifiInfo字段失败: " + fieldName, t);
        }
    }

    private BluetoothDevice createMockBluetoothDevice(String name, String address) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return null;
            if (address == null || address.isEmpty()) address = "00:00:00:00:00:00";
            BluetoothDevice device = adapter.getRemoteDevice(address);
            try {
                Field nameField = BluetoothDevice.class.getDeclaredField("mName");
                nameField.setAccessible(true);
                nameField.set(device, name);
            } catch (NoSuchFieldException e) {
                try {
                    Field nameField = BluetoothDevice.class.getDeclaredField("name");
                    nameField.setAccessible(true);
                    nameField.set(device, name);
                } catch (Exception ex) {
                    Log.e(TAG, "设置BLE设备名失败", ex);
                }
            }
            return device;
        } catch (Throwable t) {
            Log.e(TAG, "创建BLE设备失败", t);
            return null;
        }
    }

    private android.bluetooth.le.ScanResult createFakeBleScanResult(BluetoothDevice device) {
        try {
            Class<?> scanRecordClass = Class.forName("android.bluetooth.le.ScanRecord");
            Object scanRecord = null;
            byte[] recordBytes = new byte[0];
            if (!customBleData.isEmpty()) {
                try {
                    recordBytes = hexStringToByteArray(customBleData);
                } catch (Throwable t) {
                    Log.e(TAG, "广播数据解析失败", t);
                }
            }
            try {
                scanRecord = XposedHelpers.callStaticMethod(scanRecordClass, "parseFromBytes", recordBytes);
            } catch (Throwable t) {
                Log.e(TAG, "parseFromBytes失败", t);
            }
            if (scanRecord == null) {
                try {
                    java.lang.reflect.Constructor<?> ctor = scanRecordClass.getDeclaredConstructor(
                            byte[].class, int.class, int.class);
                    ctor.setAccessible(true);
                    scanRecord = ctor.newInstance(recordBytes, 0, recordBytes.length);
                } catch (Throwable t) {
                    Log.e(TAG, "反射构造ScanRecord失败", t);
                }
            }
            if (scanRecord == null) {
                Log.e(TAG, "无法创建ScanRecord");
                return null;
            }
            android.bluetooth.le.ScanResult result = null;
            long timestampNanos = System.nanoTime() * 1000;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    java.lang.reflect.Constructor<?> ctor = android.bluetooth.le.ScanResult.class.getDeclaredConstructor(
                            BluetoothDevice.class, scanRecordClass, int.class, long.class,
                            int.class, int.class, int.class, int.class, int.class, int.class);
                    ctor.setAccessible(true);
                    result = (android.bluetooth.le.ScanResult) ctor.newInstance(
                            device, scanRecord, -50, timestampNanos,
                            1, 7, 0, 255, -127, 0);
                } catch (Throwable t) {
                    Log.e(TAG, "Android 10+ ScanResult构造失败", t);
                }
            }
            if (result == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    java.lang.reflect.Constructor<?> ctor = android.bluetooth.le.ScanResult.class.getDeclaredConstructor(
                            BluetoothDevice.class, scanRecordClass, int.class, long.class, int.class);
                    ctor.setAccessible(true);
                    result = (android.bluetooth.le.ScanResult) ctor.newInstance(
                            device, scanRecord, -50, timestampNanos, 1);
                } catch (Throwable t) {
                    Log.e(TAG, "Android 8+ ScanResult构造失败", t);
                }
            }
            if (result == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    java.lang.reflect.Constructor<?> ctor = android.bluetooth.le.ScanResult.class.getDeclaredConstructor(
                            BluetoothDevice.class, scanRecordClass, int.class, long.class);
                    ctor.setAccessible(true);
                    result = (android.bluetooth.le.ScanResult) ctor.newInstance(
                            device, scanRecord, -50, timestampNanos);
                } catch (Throwable t) {
                    Log.e(TAG, "Android 5+ ScanResult构造失败", t);
                }
            }
            if (result != null) {
                Log.e(TAG, "BLE ScanResult创建成功");
            }
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "创建BLE ScanResult失败", t);
            return null;
        }
    }

    private byte[] hexStringToByteArray(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ======================== 配置读写 ========================

    private void loadPrefs() {
        if (appContext == null) return;
        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        locationEnabled = sh.getBoolean("locationEnabled", false);
        wifiEnabled = sh.getBoolean("wifiEnabled", false);
        bleEnabled = sh.getBoolean("bleEnabled", false);
        cameraEnabled = sh.getBoolean("cameraEnabled", false);
        cameraMode = sh.getInt("cameraMode", 0);
        customLat = sh.getString("lat", "");
        customLng = sh.getString("lng", "");
        customWifiSSID = sh.getString("wifiSSID", "");
        customWifiBSSID = sh.getString("wifiBSSID", "");
        customBleName = sh.getString("bleName", "");
        customBleAddress = sh.getString("bleAddress", "");
        customBleData = sh.getString("bleData", "");
        singleImagePath = sh.getString("singleImagePath", "");
        currentImageIndex = sh.getInt("currentImageIndex", 0);
        String multiPathsJson = sh.getString("multiImagePaths", "[]");
        try {
            JSONArray arr = new JSONArray(multiPathsJson);
            multiImagePaths.clear();
            for (int i = 0; i < arr.length(); i++) {
                multiImagePaths.add(arr.getString(i));
            }
        } catch (Exception e) {
            multiImagePaths.clear();
        }
        Log.e(TAG, "配置已加载: loc=" + locationEnabled + " wifi=" + wifiEnabled + " ble=" + bleEnabled + " cam=" + cameraEnabled);
    }

    private void savePrefs() {
        if (appContext == null) return;
        SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean("locationEnabled", locationEnabled);
        editor.putBoolean("wifiEnabled", wifiEnabled);
        editor.putBoolean("bleEnabled", bleEnabled);
        editor.putBoolean("cameraEnabled", cameraEnabled);
        editor.putInt("cameraMode", cameraMode);
        editor.putString("lat", customLat);
        editor.putString("lng", customLng);
        editor.putString("wifiSSID", customWifiSSID);
        editor.putString("wifiBSSID", customWifiBSSID);
        editor.putString("bleName", customBleName);
        editor.putString("bleAddress", customBleAddress);
        editor.putString("bleData", customBleData);
        editor.putString("singleImagePath", singleImagePath);
        editor.putInt("currentImageIndex", currentImageIndex);
        JSONArray arr = new JSONArray();
        for (String path : multiImagePaths) {
            arr.put(path);
        }
        editor.putString("multiImagePaths", arr.toString());
        editor.apply();
        Log.e(TAG, "配置已保存");
    }

    // ---- UI辅助 ----

    private TextView createLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 12;
        params.bottomMargin = 4;
        tv.setLayoutParams(params);
        return tv;
    }

    private EditText createEditText(Context ctx, String text, String hint) {
        EditText et = new EditText(ctx);
        et.setText(text);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        et.setPadding(16, 12, 16, 12);
        et.setBackgroundColor(0xFFF0F0F0);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(params);
        return et;
    }

    private void addDivider(LinearLayout layout) {
        View divider = new View(layout.getContext());
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = 12;
        params.bottomMargin = 12;
        divider.setLayoutParams(params);
        layout.addView(divider);
    }

    private void showFallbackDialog(Context ctx) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("Hook设置")
                    .setMessage("悬浮窗权限未授予，请授予后重试。\n\n" +
                            "当前状态:\n" +
                            "定位: " + (locationEnabled ? "开启" : "关闭") + "\n" +
                            "WiFi: " + (wifiEnabled ? "开启" : "关闭") + "\n" +
                            "蓝牙: " + (bleEnabled ? "开启" : "关闭") + "\n" +
                            "相机: " + (cameraEnabled ? "开启" : "关闭"))
                    .setCancelable(true)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "显示回退Dialog失败", t);
        }
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Callback callback;
        interface Callback {
            void onTextChanged(String text);
        }
        SimpleTextWatcher(Callback callback) {
            this.callback = callback;
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            callback.onTextChanged(s.toString());
        }
        @Override
        public void afterTextChanged(android.text.Editable s) {}
    }
}
