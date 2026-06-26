package com.HookTest;

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
import android.app.Dialog;
import android.database.Cursor;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
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
    private static Activity homeActivity;
    private static final int REQ_PICK_SINGLE = 299;
    private static final int REQ_PICK_MULTI = 300;

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
    private static Activity hostActivity;
    private static ViewGroup contentParent;
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

        // 使用 Application.attach 获取 Context，同时注册 Activity 回调显示悬浮窗
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

        // 如果 Application.attach 已经执行过（FPA框架延迟加载），直接用 classLoader 获取 Context
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null && appContext == null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "通过 currentApplication 获取到Context: " + appContext);
                loadPrefs();
                hookAMapLocation(lpparam);
                hookDingTalkWiFi(lpparam);
                hookDingTalkBluetooth(lpparam);
                hookCamera(lpparam);
            }
        } catch (Throwable t) {
            Log.e(TAG, "currentApplication 获取失败", t);
        }

        // Hook HomeActivityV2 获取实例并处理相册返回
        try {
            XposedHelpers.findAndHookMethod("com.alibaba.android.rimet.biz.HomeActivityV2",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    homeActivity = (Activity) param.thisObject;
                    hostActivity = homeActivity;
                    Log.e(TAG, "HomeActivityV2 onCreate");
                }
            });
            XposedHelpers.findAndHookMethod("com.alibaba.android.rimet.biz.HomeActivityV2",
                    lpparam.classLoader, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    handleActivityResult(requestCode, resultCode, data);
                }
            });
            Log.e(TAG, "HomeActivityV2 Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook HomeActivityV2 失败", t);
        }

        // Hook BaseNameCard 头像长按
        hookBaseNameCard(lpparam);

        // Hook LaunchHomeActivity 显示悬浮窗
        try {
            XposedHelpers.findAndHookMethod("com.alibaba.android.rimet.biz.LaunchHomeActivity",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;
                    Log.e(TAG, "LaunchHomeActivity onCreate");
                    uiHandler.postDelayed(() -> {
                        try {
                            hostActivity = activity;
                            contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                            if (floatView == null) {
                                showFloatWindow(activity);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "显示悬浮窗失败", t);
                        }
                    }, 500);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook LaunchHomeActivity 失败，尝试 Hook 任意 Activity", t);
            // 兜底 Hook Activity.onResume
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                private boolean shown = false;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (shown) return;
                    Activity activity = (Activity) param.thisObject;
                    if (activity.getClass().getName().contains("alibaba")) {
                        shown = true;
                        if (homeActivity == null && activity.getClass().getName().contains("Home")) {
                            homeActivity = activity;
                        }
                        Log.e(TAG, "Activity onResume 兜底显示悬浮窗: " + activity.getClass().getName());
                        uiHandler.postDelayed(() -> {
                            try {
                                hostActivity = activity;
                                contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                                if (floatView == null) {
                                    showFloatWindow(activity);
                                }
                            } catch (Throwable t2) {
                                Log.e(TAG, "兜底显示悬浮窗失败", t2);
                            }
                        }, 500);
                    }
                }
            });
        }

        // Hook 所有钉钉 Activity 的 onActivityResult 作为图片选择结果兜底
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    if (requestCode == REQ_PICK_SINGLE || requestCode == REQ_PICK_MULTI) {
                        int resultCode = (int) param.args[1];
                        Intent data = (Intent) param.args[2];
                        handleActivityResult(requestCode, resultCode, data);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onActivityResult 失败", t);
        }
    }

    // ======================== 悬浮窗UI ========================

    @SuppressLint("ClickableViewAccessibility")
    private void showFloatWindow(Activity activity) {
        if (floatView != null) return;

        hostActivity = activity;
        contentParent = (ViewGroup) activity.findViewById(android.R.id.content);

        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
        }

        // 创建全屏容器
        final LinearLayout floatContainer = new LinearLayout(activity);
        floatContainer.setOrientation(LinearLayout.VERTICAL);
        floatContainer.setGravity(Gravity.TOP | Gravity.END);
        floatContainer.setPadding(0, statusBarHeight + 50, 16, 0);
        floatContainer.setClickable(false);
        floatContainer.setFocusable(false);

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        // 创建悬浮按钮
        final LinearLayout floatBtn = new LinearLayout(activity);
        floatBtn.setOrientation(LinearLayout.HORIZONTAL);
        floatBtn.setBackgroundColor(0xCC000000);
        floatBtn.setPadding(16, 8, 16, 8);
        floatBtn.setGravity(Gravity.CENTER);
        floatBtn.setClickable(true);
        floatBtn.setFocusable(true);

        TextView btnText = new TextView(activity);
        btnText.setText("⚙ Hook");
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        floatBtn.addView(btnText);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        floatBtn.setLayoutParams(btnParams);

        // 点击事件
        floatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel(activity);
            }
        });

        // 触摸/拖拽事件
        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final long[] startTime = new long[1];
        final boolean[] isDragging = {false};

        floatBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX[0] = (int) v.getX();
                        initialY[0] = (int) v.getY();
                        touchX[0] = event.getRawX();
                        touchY[0] = event.getRawY();
                        startTime[0] = System.currentTimeMillis();
                        isDragging[0] = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX[0]);
                        int dy = (int) (event.getRawY() - touchY[0]);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging[0] = true;
                        }
                        v.setX(initialX[0] + dx);
                        v.setY(initialY[0] + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging[0] && System.currentTimeMillis() - startTime[0] < 300) {
                            return false;
                        }
                        return true;
                }
                return false;
            }
        });

        floatContainer.addView(floatBtn);

        try {
            contentParent.addView(floatContainer, containerParams);
            floatView = floatContainer;
            Log.e(TAG, "悬浮按钮显示成功（视图注入方式）");
        } catch (Throwable t) {
            Log.e(TAG, "悬浮按钮显示失败", t);
        }
    }

    // ======================== 面板控制 ========================

    private void togglePanel(Context ctx) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(ctx);
        }
    }

    private void hidePanel() {
        if (panelView != null && contentParent != null) {
            try {
                contentParent.removeView(panelView);
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

        if (contentParent == null && hostActivity != null) {
            contentParent = (ViewGroup) hostActivity.findViewById(android.R.id.content);
        }
        if (contentParent == null && homeActivity != null) {
            hostActivity = homeActivity;
            contentParent = (ViewGroup) homeActivity.findViewById(android.R.id.content);
        }
        if (contentParent == null) {
            Log.e(TAG, "contentParent 为空，无法显示面板");
            showFallbackDialog(ctx);
            return;
        }

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

        FrameLayout.LayoutParams panelLayoutParams = new FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        panelLayoutParams.gravity = Gravity.CENTER;

        final int[] panelStartLeft = new int[1];
        final int[] panelStartTop = new int[1];
        final float[] touchStartX = new float[1];
        final float[] touchStartY = new float[1];

        titleBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        panelStartLeft[0] = panelLayoutParams.leftMargin;
                        panelStartTop[0] = panelLayoutParams.topMargin;
                        touchStartX[0] = event.getRawX();
                        touchStartY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLayoutParams.leftMargin = panelStartLeft[0] + (int) (event.getRawX() - touchStartX[0]);
                        panelLayoutParams.topMargin = panelStartTop[0] + (int) (event.getRawY() - touchStartY[0]);
                        panelLayoutParams.gravity = Gravity.TOP | Gravity.START;
                        if (contentParent != null && panelView != null) {
                            try {
                                contentParent.updateViewLayout(panelView, panelLayoutParams);
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
            contentParent.addView(mainContainer, panelLayoutParams);
            panelView = mainContainer;
            isPanelShowing = true;
            Log.e(TAG, "设置面板显示成功（视图注入方式）");
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
        statusToggle.setText(checked[0] ? "开启" : "关闭");
        statusToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
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
            checked[0] = !checked[0];
            statusToggle.setText(checked[0] ? "开启" : "关闭");
            statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
            toggleListener.onCheckedChanged(null, checked[0]);
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

        // 地图选点
        Button mapPickerBtn = new Button(ctx);
        mapPickerBtn.setText("🗺 地图选点");
        mapPickerBtn.setOnClickListener(v -> showMapPicker(ctx));
        layout.addView(mapPickerBtn);

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

    // ---- 腾讯地图Web选点 ----
    @SuppressLint("SetJavaScriptEnabled")
    private void showMapPicker(Context ctx) {
        Activity act = ctx instanceof Activity ? (Activity) ctx : homeActivity;
        if (act == null) {
            Toast.makeText(ctx, "无法打开地图选点", Toast.LENGTH_SHORT).show();
            return;
        }
        final Activity activity = act;
        Dialog dialog = new Dialog(activity);
        dialog.setTitle("地图选点");

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        WebView webView = new WebView(activity);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(webParams);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return handleMapUrl(url, dialog, activity);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMapUrl(url, dialog, activity);
            }

            private boolean handleMapUrl(String url, Dialog dialog, Context ctx) {
                if (url.startsWith("https://www.baidu.com")) {
                    Uri uri = Uri.parse(url);
                    String latng = uri.getQueryParameter("latng");
                    if (latng != null && !latng.isEmpty()) {
                        String[] parts = latng.split(",");
                        if (parts.length == 2) {
                            customLat = parts[0].trim();
                            customLng = parts[1].trim();
                            if (latEdit != null) latEdit.setText(customLat);
                            if (lngEdit != null) lngEdit.setText(customLng);
                            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            ed.putString("lat", customLat);
                            ed.putString("lng", customLng);
                            ed.apply();
                            Toast.makeText(ctx, "已选择位置: " + customLat + ", " + customLng, Toast.LENGTH_SHORT).show();
                        }
                    }
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl("https://mapapi.qq.com/web/mapComponents/locationPicker/v/index.html?search=1&type=0&backurl=https://www.baidu.com&key=54NBZ-F3IWI-2DJGQ-UXGAY-YOY2F-MXFKE");

        container.addView(webView);
        dialog.setContentView(container);

        dialog.show();
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
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

            // 单张预览图
            if (singleImagePath != null && !singleImagePath.isEmpty()) {
                imagePreviewContainer = new LinearLayout(ctx);
                imagePreviewContainer.setOrientation(LinearLayout.HORIZONTAL);

                ImageView imgView = new ImageView(ctx);
                LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(160, 160);
                imgView.setLayoutParams(imgParams);
                imgView.setBackgroundColor(0xFFEEEEEE);
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(singleImagePath);
                    if (bmp != null) imgView.setImageBitmap(bmp);
                } catch (Exception e) {
                    Log.e(TAG, "加载单张缩略图失败", e);
                }
                imagePreviewContainer.addView(imgView);
                layout.addView(imagePreviewContainer);

                TextView pathText = createLabel(ctx, singleImagePath);
                pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                pathText.setTextColor(0xFF666666);
                layout.addView(pathText);
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

            FrameLayout itemLayout = new FrameLayout(ctx);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(110, 130);
            itemParams.setMargins(4, 4, 4, 4);
            itemLayout.setLayoutParams(itemParams);

            ImageView imgView = new ImageView(ctx);
            FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(100, 100);
            imgView.setLayoutParams(imgParams);
            imgView.setBackgroundColor(0xFFEEEEEE);
            imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);

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
                imgView.setPadding(3, 3, 3, 3);
                imgView.setBackgroundColor(0xFF4CAF50);
            }

            // 序号标签
            TextView numText = new TextView(ctx);
            numText.setText(String.valueOf(i + 1));
            numText.setTextColor(0xFFFFFFFF);
            numText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            numText.setBackgroundColor(0x88000000);
            numText.setPadding(4, 2, 4, 2);
            FrameLayout.LayoutParams numParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            numParams.gravity = Gravity.TOP | Gravity.START;
            numText.setLayoutParams(numParams);

            // 删除按钮
            TextView delBtn = new TextView(ctx);
            delBtn.setText("×");
            delBtn.setTextColor(0xFFFFFFFF);
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            delBtn.setBackgroundColor(0xCCF44336);
            delBtn.setPadding(4, 0, 4, 0);
            delBtn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams delParams = new FrameLayout.LayoutParams(30, 30);
            delParams.gravity = Gravity.TOP | Gravity.END;
            delBtn.setLayoutParams(delParams);
            delBtn.setOnClickListener(v -> {
                multiImagePaths.remove(index);
                if (currentImageIndex >= multiImagePaths.size()) {
                    currentImageIndex = 0;
                }
                if (multiImagePaths.isEmpty()) {
                    cameraEnabled = false;
                }
                savePrefs();
                refreshImagePreviews(ctx);
                updateImageStatus();
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show();
            });

            // 长按拖动排序（改进版：支持任意位置调整）
            imgView.setOnLongClickListener(v -> {
                // 弹出位置调整对话框
                final String[] items = new String[multiImagePaths.size()];
                for (int j = 0; j < items.length; j++) {
                    items[j] = "位置 " + (j + 1);
                }
                new android.app.AlertDialog.Builder(ctx)
                    .setTitle("调整图片到位置")
                    .setItems(items, (dialog, which) -> {
                        if (which != index) {
                            String pathToMove = multiImagePaths.remove(index);
                            multiImagePaths.add(which, pathToMove);
                            if (currentImageIndex == index) {
                                currentImageIndex = which;
                            } else if (currentImageIndex > index && currentImageIndex <= which) {
                                currentImageIndex--;
                            } else if (currentImageIndex < index && currentImageIndex >= which) {
                                currentImageIndex++;
                            }
                            savePrefs();
                            refreshImagePreviews(ctx);
                            updateImageStatus();
                        }
                    })
                    .show();
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
            itemLayout.addView(numText);
            itemLayout.addView(delBtn);
            imagePreviewContainer.addView(itemLayout);
        }
    }

    private void updateImageStatus() {
        if (imageStatusText != null && !multiImagePaths.isEmpty()) {
            imageStatusText.setText("当前使用: 第 " + (currentImageIndex + 1) + " 张 / 共 " + multiImagePaths.size() + " 张");
        }
    }

    private void openImagePicker(Context ctx, boolean single) {
        Activity activity = homeActivity != null ? homeActivity : hostActivity;
        if (activity == null) {
            try {
                activity = (Activity) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivity");
            } catch (Throwable ignored) {}
        }
        if (activity == null) {
            Toast.makeText(ctx, "未获取到Activity，请先打开钉钉", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            if (!single && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            int reqCode = single ? REQ_PICK_SINGLE : REQ_PICK_MULTI;
            activity.startActivityForResult(intent, reqCode);
        } catch (Throwable t) {
            Log.e(TAG, "打开图片选择失败", t);
            Toast.makeText(ctx, "打开图片选择失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        final Context ctx = homeActivity != null ? homeActivity : appContext;
        if (ctx == null) return;
        if (requestCode == REQ_PICK_SINGLE) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = copyImageFromUri(ctx, uri);
                if (path != null && !path.isEmpty()) {
                    singleImagePath = path;
                    cameraEnabled = true;
                    cameraMode = 0;
                    savePrefs();
                    uiHandler.post(() -> {
                        hidePanel();
                        uiHandler.postDelayed(() -> showPanel(ctx), 100);
                        Toast.makeText(ctx, "单张图片已选择", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    Toast.makeText(ctx, "获取图片失败", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == REQ_PICK_MULTI) {
            multiImagePaths.clear();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && data.getClipData() != null) {
                android.content.ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    android.content.ClipData.Item item = clipData.getItemAt(i);
                    if (item.getUri() != null) {
                        String path = copyImageFromUri(ctx, item.getUri());
                        if (path != null && !path.isEmpty()) multiImagePaths.add(path);
                    }
                }
            } else if (data.getData() != null) {
                String path = copyImageFromUri(ctx, data.getData());
                if (path != null && !path.isEmpty()) multiImagePaths.add(path);
            }
            currentImageIndex = 0;
            cameraEnabled = true;
            cameraMode = 1;
            savePrefs();
            uiHandler.post(() -> {
                hidePanel();
                uiHandler.postDelayed(() -> showPanel(ctx), 100);
                Toast.makeText(ctx, "已选择 " + multiImagePaths.size() + " 张图片", Toast.LENGTH_SHORT).show();
            });
        }
        // 如果ReceiveClass未配置，尝试重新扫描
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getString("ReceiveClass", "").isEmpty() && appContext != null) {
            // 延迟扫描，等待ClassLoader准备就绪
            uiHandler.postDelayed(() -> {
                try {
                    // 触发一次hookCamera重新扫描（通过反射获取已保存的lpparam或重新Hook）
                    // 由于lpparam不在静态字段中，这里我们只能尝试直接加载已知类
                    String found = autoScanReceiveClass(appContext.getClassLoader());
                    if (found != null) {
                        prefs.edit().putString("ReceiveClass", found).apply();
                        Log.e(TAG, "【PicHook】延迟扫描找到水印处理类: " + found);
                    }
                } catch (Throwable ignored) {}
            }, 2000);
        }
    }

    /**
     * 从 Uri 复制图片到模块目录，返回本地文件路径
     * 兼容 Android 10+（不依赖 MediaStore.Images.Media.DATA）
     */
    private String copyImageFromUri(Context ctx, Uri uri) {
        if (appContext == null || uri == null) return null;
        try {
            File moduleDir = new File(appContext.getFilesDir(), "hook_images");
            if (!moduleDir.exists()) moduleDir.mkdirs();

            // 生成文件名
            String ext = ".jpg";
            String lastPathSegment = uri.getLastPathSegment();
            if (lastPathSegment != null) {
                int dot = lastPathSegment.lastIndexOf('.');
                if (dot > 0) ext = lastPathSegment.substring(dot);
            }
            String destName = System.currentTimeMillis() + "_" + Math.abs(uri.toString().hashCode()) + ext;
            File destFile = new File(moduleDir, destName);

            // 通过 ContentResolver 打开输入流并复制
            java.io.InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) {
                Log.e(TAG, "无法打开Uri输入流: " + uri);
                return null;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            is.close();
            fos.close();
            
            Log.e(TAG, "图片已复制到: " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "复制图片失败", t);
        }
        return null;
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
        // 不再在这里检查 cameraEnabled，改为在 beforeHookedMethod 中检查
        // 这样 Hook 总是注册，可以动态开关

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

            // Hook 水印处理类（自动扫描 + SharedPreferences配置）
            try {
                String receiveClassName = null;
                // 先尝试从SharedPreferences读取
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                receiveClassName = prefs.getString("ReceiveClass", "");
                
                // 如果未配置，尝试自动扫描
                if (receiveClassName == null || receiveClassName.isEmpty()) {
                    receiveClassName = autoScanReceiveClass(cl);
                    if (receiveClassName != null && !receiveClassName.isEmpty()) {
                        prefs.edit().putString("ReceiveClass", receiveClassName).apply();
                        Log.e(TAG, "【PicHook】自动扫描到水印处理类: " + receiveClassName);
                    }
                }
                
                if (receiveClassName != null && !receiveClassName.isEmpty()) {
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
                } else {
                    Log.e(TAG, "【PicHook】未找到水印处理类，尝试兜底Hook");
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
     * 自动扫描水印处理类（参考钉XJ插件）
     */
    private String autoScanReceiveClass(ClassLoader cl) {
        try {
            // 方法1：尝试常见的类名
            String[] knownClasses = {
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2$a",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3$a",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2$1",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3$1",
            };
            for (String className : knownClasses) {
                try {
                    Class<?> cls = cl.loadClass(className);
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        if ("a".equals(m.getName()) && m.getParameterTypes().length == 2
                                && m.getParameterTypes()[0] == Context.class
                                && m.getParameterTypes()[1] == byte[].class) {
                            Log.e(TAG, "【PicHook】找到已知水印处理类: " + className);
                            return className;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            
            // 方法2：遍历facebox包下的类（简化版，通过反射DexPathList）
            try {
                Object pathList = XposedHelpers.getObjectField(cl, "pathList");
                Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
                for (Object element : dexElements) {
                    Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                    if (dexFile != null) {
                        java.util.Enumeration<String> entries = 
                            (java.util.Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");
                        while (entries.hasMoreElements()) {
                            String entry = entries.nextElement();
                            if (entry.startsWith("com/alibaba/dingtalk/facebox/") 
                                    && entry.contains("$") 
                                    && !entry.contains("R$")
                                    && !entry.contains("BuildConfig")) {
                                try {
                                    String className = entry.replace('/', '.').replace(".class", "");
                                    Class<?> cls = cl.loadClass(className);
                                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                                        if ("a".equals(m.getName()) && m.getParameterTypes().length == 2
                                                && m.getParameterTypes()[0] == Context.class
                                                && m.getParameterTypes()[1] == byte[].class) {
                                            Log.e(TAG, "【PicHook】扫描到水印处理类: " + className);
                                            return className;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "【PicHook】Dex扫描失败: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】自动扫描失败", t);
        }
        return null;
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
            // 钉XJ插件默认路径兜底
            if (path == null || path.isEmpty()) {
                path = "/sdcard/Download/00.jpg";
            }
            File file = new File(path);
            if (!file.exists()) return null;

            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) return null;

            // EXIF旋转校正（钉XJ做法：无EXIF时旋转270度）
            int rotation = getExifRotation(path);
            if (rotation == 0) {
                // 钉钉相机通常是竖屏，无EXIF时默认旋转270度
                rotation = 270;
            }
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
            fakeBitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos);
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

    // ======================== BaseNameCard Hook ========================

    private void hookBaseNameCard(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> baseNameCardClass = XposedHelpers.findClass("com.alibaba.android.rimet.biz.BaseNameCard", lpparam.classLoader);
            for (java.lang.reflect.Constructor<?> ctor : baseNameCardClass.getDeclaredConstructors()) {
                de.robv.android.xposed.XposedBridge.hookMethod(ctor, new de.robv.android.xposed.XposedBridge.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object card = param.thisObject;
                        View avatarView = findAvatarView(card);
                        if (avatarView != null) {
                            avatarView.setOnLongClickListener(v -> {
                                if (appContext != null) {
                                    showPanel(appContext);
                                }
                                return true;
                            });
                        }
                    }
                });
            }
            Log.e(TAG, "BaseNameCard Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "BaseNameCard Hook失败", t);
        }
    }

    private View findAvatarView(Object obj) {
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            View candidate = null;
            for (Field f : fields) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof View) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("avatar") || name.contains("head") || name.contains("icon")) {
                        return (View) val;
                    }
                    if (val instanceof ImageView && candidate == null) {
                        candidate = (View) val;
                    }
                }
            }
            return candidate;
        } catch (Throwable t) {
            Log.e(TAG, "查找头像View失败", t);
        }
        return null;
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
