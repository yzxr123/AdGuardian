package org.adguardian.app.network;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AdDomainBlocklist {
    private static final String[] SUFFIXES = {
            "ad.toutiao.com",
            "bds.snssdk.com",
            "byteadverts.com",
            "pangolin-dsp-toutiao-b.com",
            "pangolin-dsp-toutiao.com",
            "pangolin-hl.snssdk.com",
            "pangolin-lf.snssdk.com",
            "pangolin-lq.snssdk.com",
            "pangolin-sdk-toutiao-b.com",
            "pangolin-sdk-toutiao.com",
            "pangolin-sdk-toutiao1.com",
            "pangolin-sdk-toutiao2.com",
            "pangolin-sdk-toutiao3.com",
            "pangolin-sdk-toutiao4.com",
            "pangolin-sdk-toutiao5.com",
            "api-access.pangolin-sdk-toutiao.com",
            "api-access.pangolin-sdk-toutiao1.com",
            "api-access.pangolin-sdk-toutiao2.com",
            "api-access.pangolin-sdk-toutiao3.com",
            "api-access.pangolin-sdk-toutiao4.com",
            "api-access.pangolin-sdk-toutiao5.com",
            "pangolin.snssdk.com",
            "panplayable-toutiao-b.com",
            "panplayable-toutiao.com",
            "pglstatp-sdk-toutiao.com",
            "pglstatp-snssdk-toutiao.com",
            "pglstatp-toutiao-b.com",
            "pglstatp-toutiao.com",
            "shoppingads.cn",
            "api.e.kuaishou.com",
            "api2.e.kuaishou.com",
            "open.e.kuaishou.com",
            "open.e.kuaishou.cn",
            "adtrack.e.kuaishou.com",
            "promotion-partner.kuaishou.com",
            "p1.adkwai.com",
            "p1-lm.adkwai.com",
            "p2-lm.adkwai.com",
            "p3-lm.adkwai.com",
            "p66-ad.adkwai.com",
            "sdk.e.qq.com",
            "mi.gdt.qq.com",
            "v.gdt.qq.com",
            "q.i.gdt.qq.com",
            "tangram.e.qq.com",
            "pgdt.gtimg.cn",
            "pgdt.ugdtimg.com",
            "public.gdtimg.com",
            "mobads.baidu.com",
            "pos.baidu.com",
            "sigmob.com",
            "api.anythinktech.com",
            "tk.anythinktech.com",
            "net.rayjump.com",
            "hybird.rayjump.com",
            "adx-tk.rayjump.com",
            "ms.applovin.com",
            "rt.applovin.com",
            "d.applovin.com",
            "ms.applvn.com",
            "rt.applvn.com",
            "d.applvn.com",
            "auction-load.unityads.unity3d.com",
            "configv2.unityads.unity3d.com",
            "publisher-config.unityads.unity3d.com",
            "o-sdk.mediation.unity3d.com",
            "ads.api.vungle.com",
            "events.ads.vungle.com",
            "telemetry.sdk.inmobi.com",
            "ads.inmobi.com",
            "adcolony.com",
            "live.chartboost.com",
            "connect.tapjoy.com",
            "placements.tapjoy.com",
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "ads.heytapmobi.com",
            "stg-data.ads.heytapmobi.com",
            "api.ad.xiaomi.com",
            "test.ad.xiaomi.com",
            "t.ad.xiaomi.com",
            "jrad.jd.com",
            "optimus-ads.amap.com"
    };

    private static final Set<String> SUFFIX_SET = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(SUFFIXES))
    );

    private AdDomainBlocklist() {
    }

    public static String match(String host) {
        String candidate = normalize(host);
        while (!candidate.isEmpty()) {
            if (SUFFIX_SET.contains(candidate)) {
                return candidate;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0 || dot + 1 >= candidate.length()) {
                return "";
            }
            candidate = candidate.substring(dot + 1);
        }
        return "";
    }

    public static boolean isBlocked(String host) {
        return !match(host).isEmpty();
    }

    public static int count() {
        return SUFFIXES.length;
    }

    private static String normalize(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
