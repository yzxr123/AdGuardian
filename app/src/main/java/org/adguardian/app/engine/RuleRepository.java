package org.adguardian.app.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleRepository {
    private static final String AMAP = "com.autonavi.minimap";
    private static final String BAIDU_MAP = "com.baidu.BaiduMap";
    private static final String TENCENT_MAP = "com.tencent.map";

    private static final Map<String, List<AppRule>> RULES = createRules();
    private static final Set<String> SUPPORTED_PACKAGES = createSupportedPackages();
    private static final Map<String, String> LAUNCH_ACTIVITY_SUFFIXES = createLaunchActivitySuffixes();
    private static final Map<String, String> DISPLAY_NAMES = createDisplayNames();

    private RuleRepository() {
    }

    public static List<AppRule> rulesFor(String packageName) {
        List<AppRule> rules = RULES.get(packageName);
        return rules == null ? Collections.emptyList() : rules;
    }

    public static Set<String> supportedPackages() {
        return SUPPORTED_PACKAGES;
    }

    public static boolean hasRulesFor(String packageName) {
        return RULES.containsKey(packageName);
    }

    public static String displayNameFor(String packageName) {
        String value = DISPLAY_NAMES.get(packageName);
        return value == null ? packageName : value;
    }

    public static int ruleCount() {
        int count = 0;
        for (List<AppRule> rules : RULES.values()) {
            count += rules.size();
        }
        return count;
    }

    public static boolean isLaunchActivity(String packageName, String windowClassName) {
        String suffix = LAUNCH_ACTIVITY_SUFFIXES.get(packageName);
        return suffix != null && !windowClassName.isEmpty() && windowClassName.endsWith(suffix);
    }

    private static Set<String> createSupportedPackages() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(RULES.keySet()));
    }

    private static Map<String, String> createDisplayNames() {
        Map<String, String> map = new HashMap<>();
        map.put(AMAP, "高德地图");
        map.put(BAIDU_MAP, "百度地图");
        map.put(TENCENT_MAP, "腾讯地图");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> createLaunchActivitySuffixes() {
        Map<String, String> map = new HashMap<>();
        map.put(AMAP, "com.autonavi.map.activity.NewMapActivity");
        map.put(BAIDU_MAP, "com.baidu.baidumaps.MapsActivity");
        map.put(TENCENT_MAP, "WelcomeActivity");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<AppRule>> createRules() {
        Map<String, List<AppRule>> map = new HashMap<>();
        map.put(AMAP, Collections.unmodifiableList(createAmapRules()));
        map.put(BAIDU_MAP, Collections.unmodifiableList(createBaiduMapRules()));
        map.put(TENCENT_MAP, Collections.unmodifiableList(createTencentMapRules()));
        return Collections.unmodifiableMap(map);
    }

    private static List<AppRule> createAmapRules() {
        List<AppRule> rules = new ArrayList<>();
        rules.add(AppRule.text(
                "amap.startup.skip",
                AdType.STARTUP,
                AppRule.Mode.CONTAINS,
                "跳过",
                true,
                "",
                10,
                500,
                300,
                0.55f,
                0.20f
        ));
        rules.add(AppRule.viewId(
                "amap.floating.message_clear",
                AdType.FLOATING,
                AMAP + ":id/msgbox_popup_clear",
                false,
                "com.autonavi.map.activity.NewMapActivity"
        ));
        rules.add(AppRule.viewId(
                "amap.popup.main_map_dialog_close",
                AdType.POPUP,
                AMAP + ":id/main_map_msg_dialog_close",
                false,
                "com.autonavi.map.activity.NewMapActivity"
        ));
        addExplicitAdCloseRules(rules, "amap");
        return rules;
    }

    private static List<AppRule> createBaiduMapRules() {
        List<AppRule> rules = new ArrayList<>();
        rules.add(AppRule.viewId(
                "baidu_map.startup.ms_skip_view",
                AdType.STARTUP,
                BAIDU_MAP + ":id/ms_skipView",
                true,
                ""
        ));
        rules.add(AppRule.text(
                "baidu_map.startup.skip_fallback",
                AdType.STARTUP,
                AppRule.Mode.CONTAINS,
                "跳过",
                true,
                "",
                10,
                500,
                300,
                0.55f,
                0.20f
        ));
        rules.add(AppRule.viewId(
                "baidu_map.popup.img_close",
                AdType.POPUP,
                BAIDU_MAP + ":id/img_close",
                false,
                "com.baidu.baidumaps.MapsActivity"
        ));
        rules.add(AppRule.viewId(
                "baidu_map.card.banner_close",
                AdType.CARD,
                BAIDU_MAP + ":id/banner_ad_close_icon",
                false,
                "com.baidu.baidumaps.MapsActivity"
        ));
        rules.add(AppRule.viewId(
                "baidu_map.card.yellow_banner_close",
                AdType.CARD,
                BAIDU_MAP + ":id/yellow_banner_close",
                false,
                "com.baidu.baidumaps.MapsActivity"
        ));
        addExplicitAdCloseRules(rules, "baidu_map");
        return rules;
    }

    private static List<AppRule> createTencentMapRules() {
        List<AppRule> rules = new ArrayList<>();
        rules.add(AppRule.viewId(
                "tencent_map.floating.home_banner_close",
                AdType.FLOATING,
                TENCENT_MAP + ":id/shrink_close_image",
                false,
                "WelcomeActivity"
        ));
        addExplicitAdCloseRules(rules, "tencent_map");
        return rules;
    }

    private static void addExplicitAdCloseRules(List<AppRule> rules, String prefix) {
        rules.add(AppRule.text(
                prefix + ".popup.close_ad",
                AdType.POPUP,
                AppRule.Mode.EXACT,
                "关闭广告",
                false,
                "",
                0,
                0,
                0,
                0.0f,
                0.0f
        ));
        rules.add(AppRule.text(
                prefix + ".popup.close_promo",
                AdType.POPUP,
                AppRule.Mode.EXACT,
                "关闭推广",
                false,
                "",
                0,
                0,
                0,
                0.0f,
                0.0f
        ));
    }
}
