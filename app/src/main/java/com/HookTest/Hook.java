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
import android.provider.DocumentsContract;
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
    private static boolean kamiVerified = false;  // 卡密是否验证通过
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
    private static int singleImageRotation = 0; // 单张图片旋转角度
    private static List<String> multiImagePaths = new ArrayList<>();
    private static List<Integer> imageRotations = new ArrayList<>(); // 每张图片的旋转角度
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

    // 已Hook的PictureCallback类去重列表
    private static java.util.List<String> hookedPictureClasses = new java.util.ArrayList<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.alibaba.android.rimet")) {
            return;
        }

        Log.e(TAG, "包名匹配，开始Hook");

        // 直接在 handleLoadPackage 中初始化并执行 Hook（不依赖 Application.attach）
        // 通过反射获取当前应用的 Context
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "handleLoadPackage 直接获取到Context");
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleLoadPackage 获取Context失败", t);
        }

        // 检查是否已有已验证的卡密（通过SharedPreferences）
        try {
            SharedPreferences kamiSp = appContext.getSharedPreferences("jjy", Context.MODE_PRIVATE);
            String savedKami = kamiSp.getString("kami", "");
            if (savedKami != null && !savedKami.isEmpty()) {
                // 有已保存的卡密，标记为待验证（弹窗会自动验证）
                Log.e(TAG, "发现已保存卡密，将在启动时自动验证");
            }
        } catch (Throwable ignored) {}

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
                            // 先调用卡密验证（会在Activity上弹出验证Dialog）
                            KamiVerify.showDialogWithCallback(activity, verified -> {
                                if (verified) {
                                    kamiVerified = true;
                                    Log.e(TAG, "卡密验证通过，激活所有功能");
                                    try {
                                        hostActivity = activity;
                                        contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                                        if (floatView == null) {
                                            showFloatWindow(activity);
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG, "显示悬浮窗失败", t);
                                    }
                                } else {
                                    Log.e(TAG, "卡密验证未通过，所有功能禁用");
                                }
                            });
                        } catch (Throwable t) {
                            Log.e(TAG, "卡密验证启动失败", t);
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
                                KamiVerify.showDialogWithCallback(activity, verified -> {
                                    if (verified) {
                                        kamiVerified = true;
                                        Log.e(TAG, "兜底卡密验证通过，激活所有功能");
                                        try {
                                            hostActivity = activity;
                                            contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                                            if (floatView == null) {
                                                showFloatWindow(activity);
                                            }
                                        } catch (Throwable t) {
                                            Log.e(TAG, "兜底显示悬浮窗失败", t);
                                        }
                                    } else {
                                        Log.e(TAG, "兜底卡密验证未通过，所有功能禁用");
                                    }
                                });
                            } catch (Throwable t) {
                                Log.e(TAG, "兜底卡密验证启动失败", t);
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
                LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(dpToPx(ctx, 140), dpToPx(ctx, 140));
                imgView.setLayoutParams(imgParams);
                imgView.setBackgroundColor(0xFFEEEEEE);
                imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(singleImagePath);
                    if (bmp != null) {
                        // 应用单张旋转角度预览
                        if (singleImageRotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(singleImageRotation);
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                        }
                        imgView.setImageBitmap(bmp);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "加载单张缩略图失败", e);
                }
                imagePreviewContainer.addView(imgView);
                layout.addView(imagePreviewContainer);

                // 旋转按钮
                Button rotateBtn = new Button(ctx);
                rotateBtn.setText("↻ 旋转90° (当前" + singleImageRotation + "°)");
                rotateBtn.setOnClickListener(v -> {
                    singleImageRotation = (singleImageRotation + 90) % 360;
                    savePrefs();
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                    Toast.makeText(ctx, "单张图片旋转 " + singleImageRotation + "°", Toast.LENGTH_SHORT).show();
                });
                layout.addView(rotateBtn);

                TextView pathText = createLabel(ctx, singleImagePath);
                pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                pathText.setTextColor(0xFF666666);
                layout.addView(pathText);
            }
        } else {
            // 多张循环模式
            layout.addView(createLabel(ctx, "多张图片循环替换:"));

            Button selectMultiBtn = new Button(ctx);
            selectMultiBtn.setText("🖼 添加图片（逐张选择）");
            selectMultiBtn.setOnClickListener(v -> openImagePicker(ctx, false));
            layout.addView(selectMultiBtn);

            // 继续添加按钮
            if (!multiImagePaths.isEmpty()) {
                Button addMoreBtn = new Button(ctx);
                addMoreBtn.setText("➕ 继续添加更多图片");
                addMoreBtn.setOnClickListener(v -> openImagePicker(ctx, false));
                layout.addView(addMoreBtn);
            }

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
                TextView dragHint = createLabel(ctx, "提示: 长按图片可调整顺序，点击↻可旋转90°");
                dragHint.setTextColor(0xFF666666);
                dragHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                layout.addView(dragHint);
                
                // 清空所有图片按钮
                Button clearAllBtn = new Button(ctx);
                clearAllBtn.setText("🗑 清空所有图片");
                clearAllBtn.setOnClickListener(v -> {
                    multiImagePaths.clear();
                    imageRotations.clear();
                    currentImageIndex = 0;
                    cameraEnabled = false;
                    savePrefs();
                    hidePanel();
                    uiHandler.postDelayed(() -> showPanel(ctx), 100);
                    Toast.makeText(ctx, "已清空所有图片", Toast.LENGTH_SHORT).show();
                });
                layout.addView(clearAllBtn);
            }
        }
    }

    private int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshImagePreviews(Context ctx) {
        if (imagePreviewContainer == null) return;
        imagePreviewContainer.removeAllViews();

        for (int i = 0; i < multiImagePaths.size(); i++) {
            final int index = i;
            String path = multiImagePaths.get(i);

            FrameLayout itemLayout = new FrameLayout(ctx);
            int itemW = dpToPx(ctx, 130);
            int itemH = dpToPx(ctx, 150);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(itemW, itemH);
            itemParams.setMargins(dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4), dpToPx(ctx, 4));
            itemLayout.setLayoutParams(itemParams);

            ImageView imgView = new ImageView(ctx);
            int imgSize = dpToPx(ctx, 120);
            FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(imgSize, imgSize);
            imgParams.gravity = Gravity.CENTER;
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
                imgView.setPadding(dpToPx(ctx, 3), dpToPx(ctx, 3), dpToPx(ctx, 3), dpToPx(ctx, 3));
                imgView.setBackgroundColor(0xFF4CAF50);
            }

            int btnSize = dpToPx(ctx, 28);
            int btnPad = dpToPx(ctx, 2);

            // 序号标签（左上角）
            TextView numText = new TextView(ctx);
            numText.setText(String.valueOf(i + 1));
            numText.setTextColor(0xFFFFFFFF);
            numText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            numText.setBackgroundColor(0xCC000000);
            numText.setPadding(btnPad, btnPad, btnPad, btnPad);
            FrameLayout.LayoutParams numParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            numParams.gravity = Gravity.TOP | Gravity.START;
            numParams.setMargins(dpToPx(ctx, 2), dpToPx(ctx, 2), 0, 0);
            numText.setLayoutParams(numParams);

            // 删除按钮（右上角）
            TextView delBtn = new TextView(ctx);
            delBtn.setText("×");
            delBtn.setTextColor(0xFFFFFFFF);
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            delBtn.setBackgroundColor(0xDDF44336);
            delBtn.setPadding(btnPad, 0, btnPad, 0);
            delBtn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams delParams = new FrameLayout.LayoutParams(btnSize, btnSize);
            delParams.gravity = Gravity.TOP | Gravity.END;
            delParams.setMargins(0, dpToPx(ctx, 2), dpToPx(ctx, 2), 0);
            delBtn.setLayoutParams(delParams);
            delBtn.setOnClickListener(v -> {
                multiImagePaths.remove(index);
                imageRotations.remove(index);
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

            // 旋转按钮（右下角）
            TextView rotateBtn = new TextView(ctx);
            rotateBtn.setText("↻");
            rotateBtn.setTextColor(0xFFFFFFFF);
            rotateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rotateBtn.setBackgroundColor(0xDD2196F3);
            rotateBtn.setPadding(btnPad, 0, btnPad, 0);
            rotateBtn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams rotParams = new FrameLayout.LayoutParams(btnSize, btnSize);
            rotParams.gravity = Gravity.BOTTOM | Gravity.END;
            rotParams.setMargins(0, 0, dpToPx(ctx, 2), dpToPx(ctx, 2));
            rotateBtn.setLayoutParams(rotParams);
            final int imgIndex = i;
            rotateBtn.setOnClickListener(v -> {
                int currentRot = imageRotations.get(imgIndex);
                int newRot = (currentRot + 90) % 360;
                imageRotations.set(imgIndex, newRot);
                savePrefs();
                refreshImagePreviews(ctx);
                Toast.makeText(ctx, "图片 " + (imgIndex + 1) + " 旋转 " + newRot + "°", Toast.LENGTH_SHORT).show();
            });

            // 长按拖动排序
            imgView.setOnLongClickListener(v -> {
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
                            int rotToMove = imageRotations.remove(index);
                            imageRotations.add(which, rotToMove);
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
            itemLayout.addView(rotateBtn);
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
            // 统一使用 ACTION_PICK，逐张选择
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            int reqCode = single ? REQ_PICK_SINGLE : REQ_PICK_MULTI;
            activity.startActivityForResult(intent, reqCode);
        } catch (Throwable t) {
            Log.e(TAG, "打开图片选择失败", t);
            Toast.makeText(ctx, "打开图片选择失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
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
            // 追加模式：不清空已有图片，新选择的图片追加到列表末尾
            if (data.getData() != null) {
                String path = copyImageFromUri(ctx, data.getData());
                if (path != null && !path.isEmpty()) {
                    multiImagePaths.add(path);
                    imageRotations.add(0);
                    cameraEnabled = true;
                    cameraMode = 1;
                    savePrefs();
                    uiHandler.post(() -> {
                        hidePanel();
                        uiHandler.postDelayed(() -> showPanel(ctx), 100);
                        Toast.makeText(ctx, "已添加第 " + multiImagePaths.size() + " 张图片", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    Toast.makeText(ctx, "获取图片失败", Toast.LENGTH_SHORT).show();
                }
            }
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
    /**
     * 从Uri获取图片路径（优先获取原始文件路径以保留EXIF，失败则复制+恢复EXIF）
     */
    private String copyImageFromUri(Context ctx, Uri uri) {
        if (uri == null) return null;
        
        // 方法1：尝试从MediaStore获取原始文件路径（保留完整EXIF，方盒插件的做法）
        String originalPath = getRealPathFromMediaStore(ctx, uri);
        if (originalPath != null && !originalPath.isEmpty()) {
            File originalFile = new File(originalPath);
            if (originalFile.exists() && originalFile.canRead()) {
                Log.e(TAG, "【PicHook】获取原始路径: " + originalPath);
                return originalPath;
            }
        }
        
        // 方法2：复制文件 + 从URI恢复EXIF
        if (appContext == null) return null;
        try {
            File moduleDir = new File(appContext.getFilesDir(), "hook_images");
            if (!moduleDir.exists()) moduleDir.mkdirs();

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
            
            // 尝试从URI恢复EXIF信息到复制后的文件
            try {
                ExifInterface uriExif = new ExifInterface(ctx.getContentResolver().openInputStream(uri));
                ExifInterface destExif = new ExifInterface(destFile.getAbsolutePath());
                // 复制方向信息
                String orientation = uriExif.getAttribute(ExifInterface.TAG_ORIENTATION);
                if (orientation != null) {
                    destExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation);
                }
                destExif.saveAttributes();
                Log.e(TAG, "【PicHook】EXIF已恢复到复制文件, orientation=" + orientation);
            } catch (Throwable exifErr) {
                Log.e(TAG, "【PicHook】恢复EXIF失败: " + exifErr.getMessage());
            }
            
            Log.e(TAG, "【PicHook】图片已复制到: " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "复制图片失败", t);
        }
        return null;
    }

    /**
     * 从MediaStore获取原始文件路径（方盒插件做法）
     * 尝试多种查询方式兼容不同Android版本
     */
    private String getRealPathFromMediaStore(Context ctx, Uri uri) {
        // 方法1：直接获取文件路径
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        try {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                try {
                    int dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                    if (cursor.moveToFirst() && dataIndex >= 0) {
                        String path = cursor.getString(dataIndex);
                        if (path != null && !path.isEmpty()) {
                            return path;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "MediaStore查询DATA失败: " + t.getMessage());
        }
        
        // 方法2：通过 Downloads Provider 查询
        try {
            if (uri.getAuthority() != null && uri.getAuthority().startsWith("com.android.providers")) {
                String[] projection = {"_data"};
                Cursor cursor = ctx.getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null) {
                    try {
                        int dataIndex = cursor.getColumnIndexOrThrow("_data");
                        if (cursor.moveToFirst() && dataIndex >= 0) {
                            String path = cursor.getString(dataIndex);
                            if (path != null && !path.isEmpty()) {
                                return path;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        } catch (Throwable ignored) {}
        
        // 方法3：通过 MediaStore.Images.Media.EXTERNAL_CONTENT_URI 匹配
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId != null) {
                String[] split = documentId.split(":");
                if (split.length == 2) {
                    String selection = MediaStore.Images.Media._ID + "=?";
                    String[] selectionArgs = {split[1]};
                    String[] projection = {MediaStore.Images.Media.DATA};
                    Cursor cursor = ctx.getApplicationContext().getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null);
                    if (cursor != null) {
                        try {
                            int dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                            if (cursor.moveToFirst() && dataIndex >= 0) {
                                String path = cursor.getString(dataIndex);
                                if (path != null && !path.isEmpty()) {
                                    return path;
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
                    if (!kamiVerified) return;
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
        Log.e(TAG, "【PicHook】start PicHook...");
        final ClassLoader cl = lpparam.classLoader;

        // ===== Hook 1: CameraActivity2 内部类 onTakePicture(Bitmap) =====
        // 方盒硬编码 $3，我们同时尝试硬编码+动态扫描
        hookCameraActivityMethod(cl, "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2");

        // ===== Hook 2: CameraActivity3 onTakePicture(Bitmap) =====
        hookCameraActivityMethod(cl, "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3");

        // ===== Hook 3: Camera.takePicture -> 级联Hook onPictureTaken =====
        // 这是方盒的核心机制
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", cl,
                    "takePicture",
                    android.hardware.Camera.ShutterCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object jpegCallback = param.args[3];
                    if (jpegCallback == null) return;
                    
                    Class<?> callbackClass = jpegCallback.getClass();
                    String className = callbackClass.getName();
                    
                    if (hookedPictureClasses.contains(className)) return;
                    hookedPictureClasses.add(className);
                    
                    Log.e(TAG, "【PicHook】Camera.takePicture callback class: " + className);
                    
                    // 使用 XposedBridge.hookMethod 直接Hook（方盒的做法）
                    hookPictureCallbackClass(callbackClass, className);
                }
            });
            Log.e(TAG, "【PicHook】Camera.takePicture Hook OK");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】Camera.takePicture Hook FAIL: " + t.getMessage());
        }

        // ===== Hook 4: 水印处理类自动扫描 =====
        hookWatermarkClass(cl);

        // ===== Hook 5: MediaStore.insertImage 兜底 =====
        try {
            XposedHelpers.findAndHookMethod(MediaStore.Images.Media.class, "insertImage",
                    android.content.ContentResolver.class, String.class, String.class, String.class,
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!kamiVerified) return;
                    if (cameraEnabled) {
                        String imagePath = (String) param.args[1];
                        replaceSavedImage(imagePath);
                    }
                }
            });
            Log.e(TAG, "【PicHook】MediaStore.insertImage Hook OK");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】MediaStore Hook FAIL: " + t.getMessage());
        }

        Log.e(TAG, "【PicHook】PicHook loaded done");
    }

    /**
     * Hook CameraActivity 的 onTakePicture(Bitmap) 方法
     * 先尝试硬编码的内部类（方盒用$3），再动态扫描$1~$20
     */
    private void hookCameraActivityMethod(ClassLoader cl, String baseClassName) {
        final String tag = "【PicHook】";
        
        // 方法1：先尝试方盒的硬编码方式
        String[] hardcoded = {"$3", "$1", "$2", "$4", "$5"};
        for (String suffix : hardcoded) {
            try {
                String fullClassName = baseClassName + suffix;
                Class<?> cls = cl.loadClass(fullClassName);
                boolean found = false;
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Bitmap.class) {
                        doHookOnTakePicture(cls, fullClassName);
                        found = true;
                        break;
                    }
                }
                if (found) return;
            } catch (Throwable ignored) {}
        }
        
        // 方法2：动态扫描所有内部类
        for (int idx = 1; idx <= 20; idx++) {
            try {
                String fullClassName = baseClassName + "$" + idx;
                Class<?> cls = cl.loadClass(fullClassName);
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Bitmap.class) {
                        doHookOnTakePicture(cls, fullClassName);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // 方法3：检查类本身是否有 onTakePicture
        try {
            Class<?> cls = cl.loadClass(baseClassName);
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if ("onTakePicture".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == Bitmap.class) {
                    doHookOnTakePicture(cls, baseClassName);
                    return;
                }
            }
        } catch (Throwable ignored) {}
        
        Log.e(TAG, tag + baseClassName + " 未找到 onTakePicture(Bitmap) 方法");
    }

    private void doHookOnTakePicture(final Class<?> cls, final String className) {
        try {
            XposedHelpers.findAndHookMethod(cls, "onTakePicture", Bitmap.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cameraEnabled) return;
                    if (!kamiVerified) return;
                    
                    String path = getCurrentImagePath();
                    if (path == null || path.isEmpty()) return;
                    
                    File file = new File(path);
                    if (!file.exists()) return;
                    
                    Bitmap bitmap = BitmapFactory.decodeFile(path);
                    if (bitmap == null) return;
                    
                    // 方盒插件精确做法：onTakePicture(Bitmap) 路径不做EXIF自动旋转
                    // 钉钉内部保存Bitmap时会自行读取原文件EXIF并处理方向
                    // 如果我们也做EXIF旋转，会导致钉钉双重旋转，图片方向错误
                    // 只应用用户手动旋转（单张模式）
                    if (cameraMode == 0 && singleImageRotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(singleImageRotation);
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    }
                    
                    param.args[0] = bitmap;
                    Log.e(TAG, "【PicHook】" + className + " Bitmap替换成功 (无EXIF自动旋转)");
                    advanceImageIndex();
                }
            });
            Log.e(TAG, "【PicHook】" + className + " Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】" + className + " Hook失败: " + t.getMessage());
        }
    }

    /**
     * 动态Hook PictureCallback实现类的 onPictureTaken 方法
     */
    private void hookPictureCallbackClass(Class<?> callbackClass, String className) {
        try {
            // 方法1：先尝试标准签名 onPictureTaken(byte[], Camera)
            try {
                XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                        byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        if (!kamiVerified) return;
                        Log.e(TAG, "【PicHook】" + className + ".onPictureTaken triggered");
                        
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[0] = fakeData;
                            Log.e(TAG, "【PicHook】" + className + " byte[]替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】" + className + " onPictureTaken(byte[],Camera) Hook成功");
                return;
            } catch (Throwable ignored) {}
            
            // 方法2：尝试 onPictureTaken(byte[]) 无Camera参数
            try {
                XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                        byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        if (!kamiVerified) return;
                        Log.e(TAG, "【PicHook】" + className + ".onPictureTaken(byte[]) triggered");
                        
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[0] = fakeData;
                            Log.e(TAG, "【PicHook】" + className + " byte[]替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】" + className + " onPictureTaken(byte[]) Hook成功");
                return;
            } catch (Throwable ignored) {}
            
            // 方法3：遍历所有方法找到 onPictureTaken
            for (java.lang.reflect.Method m : callbackClass.getDeclaredMethods()) {
                if ("onPictureTaken".equals(m.getName()) && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0] == byte[].class) {
                    XposedHelpers.findAndHookMethod(callbackClass, "onPictureTaken",
                            m.getParameterTypes(), new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cameraEnabled) return;
                            if (!kamiVerified) return;
                            String path = getCurrentImagePath();
                            if (path == null || path.isEmpty()) return;
                            byte[] fakeData = readImageFileWithExif(path);
                            if (fakeData != null) {
                                param.args[0] = fakeData;
                                Log.e(TAG, "【PicHook】" + className + " onPictureTaken 替换成功");
                                advanceImageIndex();
                            }
                        }
                    });
                    Log.e(TAG, "【PicHook】" + className + " onPictureTaken Hook成功(反射)");
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】Hook " + className + " 失败: " + t.getMessage());
        }
    }

    /**
     * Hook 水印处理类
     */
    private void hookWatermarkClass(ClassLoader cl) {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String receiveClassName = prefs.getString("ReceiveClass", "");
            
            if (receiveClassName == null || receiveClassName.isEmpty()) {
                receiveClassName = autoScanReceiveClass(cl);
                if (receiveClassName != null && !receiveClassName.isEmpty()) {
                    prefs.edit().putString("ReceiveClass", receiveClassName).apply();
                    Log.e(TAG, "【PicHook】扫描到水印处理类: " + receiveClassName);
                }
            }
            
            if (receiveClassName != null && !receiveClassName.isEmpty()) {
                Class<?> receiveClass = cl.loadClass(receiveClassName);
                XposedHelpers.findAndHookMethod(receiveClass, "a",
                        Context.class, byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cameraEnabled) return;
                        if (!kamiVerified) return;
                        String path = getCurrentImagePath();
                        if (path == null || path.isEmpty()) return;
                        byte[] fakeData = readImageFileWithExif(path);
                        if (fakeData != null) {
                            param.args[1] = fakeData;
                            Log.e(TAG, "【PicHook】ReceiveClass.a 替换成功");
                            advanceImageIndex();
                        }
                    }
                });
                Log.e(TAG, "【PicHook】ReceiveClass: " + receiveClassName + " Hook成功");
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】ReceiveClass Hook失败: " + t.getMessage());
        }
    }

    /**
     * 自动扫描水印处理类（仅尝试已知类名，不使用DexPathList遍历）
     */
    private String autoScanReceiveClass(ClassLoader cl) {
        try {
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

            // 扩展扫描更多可能的内部类
            String[] baseClasses = {
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity2",
                "com.alibaba.dingtalk.facebox.camera.activity.CameraActivity3",
            };
            for (String baseClass : baseClasses) {
                for (int idx = 1; idx <= 20; idx++) {
                    try {
                        String fullClassName = baseClass + "$" + idx;
                        Class<?> cls = cl.loadClass(fullClassName);
                        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                            if ("a".equals(m.getName()) && m.getParameterTypes().length == 2
                                    && m.getParameterTypes()[0] == Context.class
                                    && m.getParameterTypes()[1] == byte[].class) {
                                Log.e(TAG, "【PicHook】扫描到水印处理类: " + fullClassName);
                                return fullClassName;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】自动扫描失败", t);
        }
        return null;
    }

    /**
     * 获取当前应该使用的图片路径
     */
    private String getCurrentImagePath() {
        String path = null;
        if (cameraMode == 0) {
            path = singleImagePath;
        } else {
            if (!multiImagePaths.isEmpty() && currentImageIndex < multiImagePaths.size()) {
                path = multiImagePaths.get(currentImageIndex);
            }
        }
        if (path == null || path.isEmpty()) {
            path = "/sdcard/Download/00.jpg";
        }
        return path;
    }

    /**
     * 多张模式切换到下一张
     */
    private void advanceImageIndex() {
        if (cameraMode == 1 && !multiImagePaths.isEmpty()) {
            currentImageIndex = (currentImageIndex + 1) % multiImagePaths.size();
            savePrefs();
        }
    }

    /**
     * 读取图片文件为byte[]（带EXIF旋转处理，方盒 n.a.c 的做法）
     */
    private byte[] readImageFileWithExif(String path) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) {
                Log.e(TAG, "【PicHook】readImageFileWithExif: decodeFile返回null");
                return null;
            }
            
            // 方盒插件 n.a.c 的精确做法：
            // EXIF无旋转(Orientation=1)的图片 → 强制旋转270度
            // EXIF有旋转(Orientation=3/6/8)的图片 → 保持原样不旋转
            // 这是针对钉钉Camera API的特定方向补偿
            int exifRotation = getExifRotation(path);
            if (exifRotation == 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(270);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                Log.e(TAG, "【PicHook】readImageFileWithExif: EXIF正常，强制旋转270°(方盒做法)");
            }
            
            // 叠加用户手动旋转
            int pathIndex = multiImagePaths.indexOf(path);
            if (pathIndex >= 0 && pathIndex < imageRotations.size()) {
                int manualRot = imageRotations.get(pathIndex);
                if (manualRot != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(manualRot);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    Log.e(TAG, "【PicHook】readImageFileWithExif: 叠加手动旋转 " + manualRot + "°");
                }
            }
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "【PicHook】readImageFileWithExif 失败", t);
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

    private Object createFakePictureCallback(Object originalCallback) {
        try {
            Class<?> pictureCallbackClass = Class.forName("android.hardware.Camera$PictureCallback");
            return java.lang.reflect.Proxy.newProxyInstance(
                    originalCallback.getClass().getClassLoader(),
                    new Class<?>[]{pictureCallbackClass},
                    (proxy, method, args) -> {
                        if ("onPictureTaken".equals(method.getName()) && args.length > 0) {
                            if (!kamiVerified) return method.invoke(originalCallback, args);
                            String picPath = getCurrentImagePath();
                            if (picPath != null && !picPath.isEmpty()) {
                                byte[] fakeData = readImageFileWithExif(picPath);
                                if (fakeData != null) {
                                    args[0] = fakeData;
                                    advanceImageIndex();
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
            String path = getCurrentImagePath();
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                if (file.exists()) {
                    byte[] data = readImageFileWithExif(path);
                    if (data != null) {
                        Log.e(TAG, "getFakeImageData: 已加载 " + path);
                        return data;
                    }
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
        singleImageRotation = sh.getInt("singleImageRotation", 0);
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
        // 加载多张图片旋转角度
        String rotationsJson = sh.getString("imageRotations", "[]");
        try {
            JSONArray rotArr = new JSONArray(rotationsJson);
            imageRotations.clear();
            for (int i = 0; i < rotArr.length(); i++) {
                imageRotations.add(rotArr.getInt(i));
            }
        } catch (Exception e) {
            imageRotations.clear();
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
        editor.putInt("singleImageRotation", singleImageRotation);
        editor.putInt("currentImageIndex", currentImageIndex);
        JSONArray arr = new JSONArray();
        for (String path : multiImagePaths) {
            arr.put(path);
        }
        editor.putString("multiImagePaths", arr.toString());
        // 保存旋转角度
        JSONArray rotArr = new JSONArray();
        for (int rot : imageRotations) {
            rotArr.put(rot);
        }
        editor.putString("imageRotations", rotArr.toString());
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

    /**
     * 卡密验证系统 - 纯Java原生实现
     * 不依赖classes2.dex中的任何类
     */
    public static class KamiVerify {

        private static final String KAMI_APPID  = "10000";
        private static final String KAMI_APPKEY = "VjVj60L9L1E6eM6t";
        private static final String KAMI_RC4_KEY = "Y2a3RBZAMWD10000";
        private static final String KAMI_API_BASE = "http://zy.luckyyh.top/api.php?api=kmlogon";
        private static final String KAMI_SP_NAME = "jjy";
        private static final String KAMI_SP_KEY = "kami";

        private static android.app.AlertDialog sCurrentDialog = null;

        public interface VerifyCallback {
            void onResult(boolean verified);
        }

        private static VerifyCallback sCallback = null;

        public static void showDialogWithCallback(Context context, VerifyCallback callback) {
            sCallback = callback;
            showDialog(context);
        }

        public static void showDialog(final Context context) {
            if (context == null) return;
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> showDialogInternal(context));
            } else {
                showDialogInternal(context);
            }
        }

        public static void requestVerify(Context context, String kami) {
            if (context == null || kami == null || kami.trim().isEmpty()) return;
            final String trimKami = kami.trim();
            new Thread(() -> doNetworkRequest(context, trimKami)).start();
        }

        private static void showDialogInternal(final Context context) {
            try {
                android.widget.LinearLayout root = new android.widget.LinearLayout(context);
                root.setOrientation(android.widget.LinearLayout.VERTICAL);
                root.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT));

                android.graphics.drawable.GradientDrawable rootBg = new android.graphics.drawable.GradientDrawable();
                rootBg.setColor(0xFFF6F6F6);
                root.setBackground(rootBg);

                android.graphics.drawable.GradientDrawable titleBg = new android.graphics.drawable.GradientDrawable();
                titleBg.setStroke(2, 0xFFDCDFE6);
                titleBg.setColor(0xFFF6F6F6);

                android.widget.TextView titleView = new android.widget.TextView(context);
                titleView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                titleView.setGravity(android.view.Gravity.CENTER);
                titleView.setText("欢迎使用");
                titleView.setTextColor(android.graphics.Color.BLACK);
                titleView.setPadding(30, 30, 30, 30);
                titleView.setTextSize(20f);
                titleView.setBackground(titleBg);

                android.widget.TextView hintView = new android.widget.TextView(context);
                hintView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                hintView.setText("请在此处输入卡密");
                hintView.setTextColor(android.graphics.Color.BLACK);
                hintView.setPadding(40, 40, 40, 40);

                android.widget.LinearLayout inputArea = new android.widget.LinearLayout(context);
                inputArea.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                inputArea.setPadding(25, 25, 25, 25);

                final android.widget.EditText editText = new android.widget.EditText(context);
                editText.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                editText.setHint("输入卡密");
                editText.setTextColor(0xFF1234E7);
                editText.setPadding(10, 10, 10, 10);
                editText.setTextSize(15f);
                editText.setBackgroundColor(android.graphics.Color.WHITE);
                inputArea.addView(editText);

                android.widget.LinearLayout btnArea = new android.widget.LinearLayout(context);
                btnArea.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                btnArea.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

                android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

                android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
                btnBg.setStroke(2, 0xFFDCDFE6);
                btnBg.setColor(0xFFF6F6F6);

                android.widget.Button cancelBtn = new android.widget.Button(context);
                cancelBtn.setLayoutParams(btnParams);
                cancelBtn.setText("取消");
                cancelBtn.setTextColor(0xFF1234E7);
                cancelBtn.setPadding(30, 30, 30, 30);
                cancelBtn.setTextSize(15f);
                cancelBtn.setBackground(btnBg);

                android.widget.Button activeBtn = new android.widget.Button(context);
                activeBtn.setLayoutParams(btnParams);
                activeBtn.setText("激活");
                activeBtn.setTextColor(0xFF1234E7);
                activeBtn.setPadding(30, 30, 30, 30);
                activeBtn.setTextSize(15f);
                activeBtn.setBackground(btnBg);

                btnArea.addView(cancelBtn);
                btnArea.addView(activeBtn);

                root.addView(titleView);
                root.addView(hintView);
                root.addView(inputArea);
                root.addView(btnArea);

                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context,
                        android.R.style.Theme_Material_Light_Dialog_Alert);
                builder.setCancelable(false);
                builder.setView(root);

                if (sCurrentDialog != null && sCurrentDialog.isShowing()) {
                    sCurrentDialog.dismiss();
                }
                sCurrentDialog = builder.show();
                final android.app.AlertDialog dialogRef = sCurrentDialog;

                activeBtn.setOnClickListener(v -> {
                    String input = editText.getText().toString().trim();
                    if (!input.isEmpty()) {
                        requestVerify(context, input);
                    } else {
                        android.widget.Toast.makeText(context, "卡密为空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });

                cancelBtn.setOnClickListener(v -> {
                    // 取消按钮不做任何事，弹窗不可关闭
                    android.widget.Toast.makeText(context, "请先完成卡密激活", android.widget.Toast.LENGTH_SHORT).show();
                });

                String savedKami = spGet(context, KAMI_SP_KEY);
                if (savedKami != null && !savedKami.isEmpty()) {
                    editText.setText(savedKami);
                    requestVerify(context, savedKami);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            // 返回键拦截，防止关闭弹窗
            if (sCurrentDialog != null) {
                sCurrentDialog.setOnKeyListener((dialog, keyCode, event) -> {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        return true; // 拦截返回键，防止关闭弹窗
                    }
                    return false;
                });
            }
        }

        private static void doNetworkRequest(Context context, String kami) {
            java.net.HttpURLConnection conn = null;
            try {
                String androidId = android.provider.Settings.System.getString(context.getContentResolver(), "android_id");
                if (androidId == null || androidId.isEmpty()) {
                    androidId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                }
                if (androidId == null) androidId = "";

                String timestamp = String.valueOf(System.currentTimeMillis());
                String signBase = "kami=" + kami + "&markcode=" + androidId + "&t=" + timestamp + "&" + KAMI_APPKEY;
                String sign = md5(signBase);
                String plainData = "&kami=" + kami + "&markcode=" + androidId + "&t=" + timestamp + "&sign=" + sign;
                String encryptedData = rc4EncryptToHex(plainData, KAMI_RC4_KEY);
                String urlStr = KAMI_API_BASE + "&app=" + KAMI_APPID
                        + "&data=" + java.net.URLEncoder.encode(encryptedData, "UTF-8")
                        + "&sign=" + sign;

                java.net.URL url = new java.net.URL(urlStr);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoInput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                int responseCode = conn.getResponseCode();
                java.io.InputStream is = (responseCode == java.net.HttpURLConnection.HTTP_OK)
                        ? conn.getInputStream() : conn.getErrorStream();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String responseBody = sb.toString();
                String decrypted = rc4DecryptFromHex(responseBody, KAMI_RC4_KEY);
                if (decrypted == null || decrypted.isEmpty()) {
                    showToastOnMain(context, "服务器响应解析失败");
                    return;
                }

                org.json.JSONObject json = new org.json.JSONObject(decrypted);
                String code = json.optString("code", "");
                String msg = json.optString("msg", "");

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    handleResponse(context, code, msg, json, kami);
                });

            } catch (Exception e) {
                showToastOnMain(context, "服务器连接失败");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static void handleResponse(Context context, String code, String msg, org.json.JSONObject json, String kami) {
            switch (code) {
                case "200":
                    try {
                        String kamiValue = json.optString("kami", kami);
                        String vipTs = json.optString("vip", "0");
                        long vipTime = Long.parseLong(vipTs) * 1000L;
                        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                .format(new java.util.Date(vipTime));
                        android.widget.Toast.makeText(context, "卡密到期时间:" + dateStr, android.widget.Toast.LENGTH_LONG).show();
                        spPut(context, KAMI_SP_KEY, kamiValue);
                        if (sCurrentDialog != null && sCurrentDialog.isShowing()) {
                            sCurrentDialog.dismiss();
                            sCurrentDialog = null;
                        }
                        // 通知回调：验证通过
                        if (sCallback != null) {
                            sCallback.onResult(true);
                            sCallback = null;
                        }
                    } catch (Exception e) {
                        android.widget.Toast.makeText(context, "验证成功但解析到期时间失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    break;
                case "101":
                    android.widget.Toast.makeText(context, "应用不存在", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "102":
                    android.widget.Toast.makeText(context, "应用已关闭", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "104":
                    android.widget.Toast.makeText(context, "接口维护中", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "105":
                    android.widget.Toast.makeText(context, "接口未添加或不存在", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "106":
                    android.widget.Toast.makeText(context, "签名为空", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "148":
                    android.widget.Toast.makeText(context, "数据过期", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "149":
                    android.widget.Toast.makeText(context, "签名有误", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "151":
                    android.widget.Toast.makeText(context, "卡密为空", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "169":
                    android.widget.Toast.makeText(context, "卡密不存在", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "171":
                    android.widget.Toast.makeText(context, "卡密禁用", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                case "172":
                    android.widget.Toast.makeText(context, "IP不一致", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                default:
                    android.widget.Toast.makeText(context, msg != null && !msg.isEmpty() ? msg : "未知错误", android.widget.Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        private static void showToastOnMain(final Context context, final String text) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (context != null) {
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        private static String rc4EncryptToHex(String plain, String key) {
            if (plain == null || key == null) return null;
            try {
                byte[] data = plain.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] out = rc4Base(data, key);
                return bytesToHex(out);
            } catch (Exception e) {
                return null;
            }
        }

        private static String rc4DecryptFromHex(String hex, String key) {
            if (hex == null || key == null) return null;
            try {
                byte[] data = hexToBytes(hex);
                byte[] out = rc4Base(data, key);
                return new String(out, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        private static byte[] rc4Base(byte[] data, String key) {
            byte[] s = initRc4Key(key);
            int i = 0, j = 0;
            byte[] out = new byte[data.length];
            for (int k = 0; k < data.length; k++) {
                i = (i + 1) & 0xFF;
                j = (j + (s[i] & 0xFF)) & 0xFF;
                byte tmp = s[i];
                s[i] = s[j];
                s[j] = tmp;
                int t = ((s[i] & 0xFF) + (s[j] & 0xFF)) & 0xFF;
                out[k] = (byte) (data[k] ^ s[t]);
            }
            return out;
        }

        private static byte[] initRc4Key(String key) {
            byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] s = new byte[256];
            for (int i = 0; i < 256; i++) {
                s[i] = (byte) i;
            }
            int j = 0;
            for (int i = 0; i < 256; i++) {
                j = (j + (s[i] & 0xFF) + (keyBytes[i % keyBytes.length] & 0xFF)) & 0xFF;
                byte tmp = s[i];
                s[i] = s[j];
                s[j] = tmp;
            }
            return s;
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        }

        private static byte[] hexToBytes(String hex) {
            if (hex == null) return null;
            int len = hex.length();
            if (len % 2 != 0) {
                hex = "0" + hex;
                len++;
            }
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                        + Character.digit(hex.charAt(i + 1), 16));
            }
            return data;
        }

        private static String md5(String input) {
            if (input == null) return "";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    int v = b & 0xFF;
                    if (v < 16) sb.append('0');
                    sb.append(Integer.toHexString(v));
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        private static void spPut(Context context, String key, String value) {
            if (context == null) return;
            android.content.SharedPreferences sp = context.getSharedPreferences(KAMI_SP_NAME, android.content.Context.MODE_PRIVATE);
            sp.edit().putString(key, value).apply();
        }

        private static String spGet(Context context, String key) {
            if (context == null) return "";
            android.content.SharedPreferences sp = context.getSharedPreferences(KAMI_SP_NAME, android.content.Context.MODE_PRIVATE);
            return sp.getString(key, "");
        }
    }
}
