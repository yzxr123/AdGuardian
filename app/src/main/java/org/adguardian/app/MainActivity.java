package org.adguardian.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.adguardian.app.debug.DebugLogStore;
import org.adguardian.app.engine.AdSdkSignatures;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.RuleRepository;
import org.adguardian.app.network.AdDomainBlocklist;
import org.adguardian.app.network.LocalDnsVpnService;
import org.adguardian.app.service.AdAccessibilityService;
import org.adguardian.app.settings.PreferenceStore;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 4103;

    private TextView serviceStatus;
    private TextView networkStatus;
    private Button networkButton;
    private TextView debugLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        DebugLogStore.info(this, "UI_OPEN", "AdGuardian B4 test UI opened");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateNetworkStatus();
        refreshDebugLog();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_PERMISSION_REQUEST) {
            return;
        }
        if (resultCode == RESULT_OK) {
            PreferenceStore.setTypeEnabled(this, AdType.NETWORK, true);
            LocalDnsVpnService.start(this);
            DebugLogStore.info(this, "VPN_PERMISSION", "granted=true");
        } else {
            DebugLogStore.miss(this, "VPN_PERMISSION", "granted=false");
        }
        updateNetworkStatus();
    }

    private View createContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(250, 250, 250));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setContentDescription("AdGuardian");
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(icon, iconParams);

        TextView title = text("AdGuardian", 28, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, marginTop(dp(8)));

        TextView subtitle = text("本机广告权益保护", 14, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, marginTop(dp(4)));

        addSpace(root, 22);

        serviceStatus = text("无障碍服务 未开启", 16, true);
        root.addView(serviceStatus);

        Button serviceButton = new Button(this);
        serviceButton.setText("打开无障碍设置");
        serviceButton.setAllCaps(false);
        serviceButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(serviceButton, marginTop(dp(10)));

        addSpace(root, 20);
        root.addView(sectionTitle("保护开关"));

        addMasterSwitch(root);
        for (AdType type : AdType.values()) {
            addTypeSwitch(root, type);
        }

        addNetworkSection(root);

        addSpace(root, 20);
        root.addView(sectionTitle("保护范围"));
        TextView apps = text(
                "L1 无障碍通用识别  所有非浏览器第三方 App\n"
                        + "L2 本地中文 OCR  Android 11+\n"
                        + "L3 本地 YOLO11n  ncnn Vulkan/CPU\n"
                        + "广告 SDK 共性签名  " + AdSdkSignatures.resourceMarkerCount() + " 组资源标记  "
                        + AdSdkSignatures.activityMarkerCount() + " 组 Activity 标记\n"
                        + "本机 DNS 高置信广告域名  " + AdDomainBlocklist.count() + " 条\n"
                        + "地图专项补强  高德地图  百度地图  腾讯地图\n"
                        + "专项规则  " + RuleRepository.ruleCount() + " 条",
                15,
                false
        );
        root.addView(apps, marginTop(dp(8)));

        TextView note = text(
                "B4 使用 L1 无障碍 → L2 本地 OCR → L3 本地 YOLO 短路链路  开屏阶段主动轮询补足不触发无障碍事件的广告  网络层仍只接管 DNS  不做 HTTPS 中间人",
                13,
                false
        );
        note.setTextColor(Color.DKGRAY);
        root.addView(note, marginTop(dp(10)));

        if (BuildConfig.DEBUG) {
            addDebugSection(root);
        }

        addSpace(root, 16);
        TextView local = text("无账号  无自建服务器  无遥测  所有规则随 APK 本地运行", 13, false);
        local.setTextColor(Color.DKGRAY);
        root.addView(local);

        return scrollView;
    }

    private void addNetworkSection(LinearLayout root) {
        addSpace(root, 20);
        root.addView(sectionTitle("网络增强保护"));

        networkStatus = text("本机 DNS 过滤 未启动", 15, true);
        root.addView(networkStatus, marginTop(dp(4)));

        TextView detail = text(
                "首次开启只需要确认一次 Android VPN 权限  不需要安装其他 App  该模式会占用系统唯一 VPN 槽位  只拦截内置高置信广告域名",
                13,
                false
        );
        detail.setTextColor(Color.DKGRAY);
        root.addView(detail, marginTop(dp(6)));

        networkButton = new Button(this);
        networkButton.setAllCaps(false);
        networkButton.setText("开启网络过滤");
        networkButton.setOnClickListener(v -> {
            if (LocalDnsVpnService.isRunning()) {
                LocalDnsVpnService.stop(this);
                DebugLogStore.info(this, "VPN_BUTTON", "action=stop");
                networkButton.postDelayed(this::updateNetworkStatus, 180L);
            } else {
                requestNetworkFilter();
            }
        });
        root.addView(networkButton, marginTop(dp(8)));
    }

    private void requestNetworkFilter() {
        PreferenceStore.setTypeEnabled(this, AdType.NETWORK, true);
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            DebugLogStore.info(this, "VPN_PERMISSION", "requesting=true");
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
        } else {
            LocalDnsVpnService.start(this);
            DebugLogStore.info(this, "VPN_PERMISSION", "already-granted=true");
            networkButton.postDelayed(this::updateNetworkStatus, 180L);
        }
    }

    private void addDebugSection(LinearLayout root) {
        addSpace(root, 20);
        root.addView(sectionTitle("测试日志"));

        TextView hint = text(
                "复现广告后重点查看 GENERIC_BLOCKED  L2_OCR_BLOCKED  L3_YOLO_BLOCKED  AD_EVIDENCE  DNS_BLOCKED 以及 *_FAILED 或 *_CANCELLED",
                13,
                false
        );
        hint.setTextColor(Color.DKGRAY);
        root.addView(hint, marginTop(dp(6)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons, marginTop(dp(8)));

        Button refresh = debugButton("刷新日志");
        refresh.setOnClickListener(v -> refreshDebugLog());
        buttons.addView(refresh, weightedButtonParams());

        Button copy = debugButton("复制日志");
        copy.setOnClickListener(v -> copyDebugLog());
        buttons.addView(copy, weightedButtonParams());

        Button clear = debugButton("清空日志");
        clear.setOnClickListener(v -> {
            DebugLogStore.clear(this);
            DebugLogStore.info(this, "LOG_CLEARED", "test log cleared by user");
            refreshDebugLog();
        });
        buttons.addView(clear, weightedButtonParams());

        debugLogView = text("暂无日志", 11, false);
        debugLogView.setTypeface(Typeface.MONOSPACE);
        debugLogView.setTextIsSelectable(true);
        debugLogView.setTextColor(Color.rgb(35, 35, 35));
        debugLogView.setBackgroundColor(Color.rgb(238, 238, 238));
        debugLogView.setPadding(dp(10), dp(10), dp(10), dp(10));
        debugLogView.setMinHeight(dp(180));
        root.addView(debugLogView, marginTop(dp(8)));
    }

    private Button debugButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private void copyDebugLog() {
        String value = DebugLogStore.read(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("AdGuardian test log", value));
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
    }

    private void refreshDebugLog() {
        if (debugLogView != null) {
            debugLogView.setText(DebugLogStore.read(this));
        }
    }

    private void addMasterSwitch(LinearLayout root) {
        Switch toggle = createSwitch("广告保护", PreferenceStore.isMasterEnabled(this));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PreferenceStore.setMasterEnabled(this, isChecked);
            DebugLogStore.info(this, "MASTER_SWITCH", "enabled=" + isChecked);
            if (!isChecked && LocalDnsVpnService.isRunning()) {
                LocalDnsVpnService.stop(this);
            } else if (isChecked
                    && PreferenceStore.isTypeEnabled(this, AdType.NETWORK)
                    && !LocalDnsVpnService.isRunning()) {
                requestNetworkFilter();
            }
            updateNetworkStatus();
        });
        root.addView(toggle);
    }

    private void addTypeSwitch(LinearLayout root, AdType type) {
        Switch toggle = createSwitch(type.displayName(), PreferenceStore.isTypeEnabled(this, type));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PreferenceStore.setTypeEnabled(this, type, isChecked);
            DebugLogStore.info(this, "TYPE_SWITCH", "type=" + type.displayName() + " enabled=" + isChecked);
            if (type == AdType.NETWORK) {
                if (isChecked && PreferenceStore.isMasterEnabled(this)) {
                    requestNetworkFilter();
                } else if (!isChecked && LocalDnsVpnService.isRunning()) {
                    LocalDnsVpnService.stop(this);
                }
                updateNetworkStatus();
            }
        });
        root.addView(toggle);
    }

    private Switch createSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(16);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(6), 0, dp(6));
        toggle.setShowText(false);
        return toggle;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 14, true);
        title.setTextColor(Color.rgb(80, 80, 80));
        title.setPadding(0, 0, 0, dp(6));
        return title;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(20, 20, 20));
        if (bold) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = top;
        return params;
    }

    private void addSpace(LinearLayout root, int dp) {
        Space space = new Space(this);
        root.addView(space, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateServiceStatus() {
        if (serviceStatus == null) {
            return;
        }
        if (isServiceEnabled()) {
            serviceStatus.setText("无障碍服务 已开启");
            serviceStatus.setTextColor(Color.rgb(20, 120, 60));
        } else {
            serviceStatus.setText("无障碍服务 未开启");
            serviceStatus.setTextColor(Color.rgb(160, 50, 50));
        }
    }

    private void updateNetworkStatus() {
        if (networkStatus == null || networkButton == null) {
            return;
        }
        if (LocalDnsVpnService.isRunning()) {
            networkStatus.setText("本机 DNS 过滤 已开启");
            networkStatus.setTextColor(Color.rgb(20, 120, 60));
            networkButton.setText("停止网络过滤");
        } else if (!PreferenceStore.isTypeEnabled(this, AdType.NETWORK)) {
            networkStatus.setText("本机 DNS 过滤 类型开关已关闭");
            networkStatus.setTextColor(Color.rgb(120, 90, 30));
            networkButton.setText("开启网络过滤");
        } else {
            networkStatus.setText("本机 DNS 过滤 未启动");
            networkStatus.setTextColor(Color.rgb(160, 50, 50));
            networkButton.setText("开启网络过滤");
        }
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        );
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String packageName = info.getResolveInfo().serviceInfo.packageName;
            String serviceName = info.getResolveInfo().serviceInfo.name;
            if (getPackageName().equals(packageName)
                    && AdAccessibilityService.class.getName().equals(serviceName)) {
                return true;
            }
        }
        return false;
    }
}
