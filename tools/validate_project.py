from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []


def require(condition, message):
    if not condition:
        errors.append(message)


def text(path):
    return (ROOT / path).read_text(encoding="utf-8")


def workflow_text():
    candidates = [
        ROOT / ".github/workflows/build-apk.yml",
        ROOT.parent / ".github/workflows/build-apk.yml",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8")
    errors.append("GitHub Actions workflow is missing")
    return ""


manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
config_path = ROOT / "app/src/main/res/xml/accessibility_service_config.xml"
ET.parse(manifest_path)
ET.parse(config_path)

manifest = manifest_path.read_text(encoding="utf-8")
config = config_path.read_text(encoding="utf-8")
app_build = text("app/build.gradle")
workflow = workflow_text()
service = text("app/src/main/java/org/adguardian/app/service/AdAccessibilityService.java")
policy = text("app/src/main/java/org/adguardian/app/engine/PackagePolicy.java")
rule_engine = text("app/src/main/java/org/adguardian/app/engine/RuleEngine.java")
generic = text("app/src/main/java/org/adguardian/app/engine/GenericAdEngine.java")
sdk_signatures = text("app/src/main/java/org/adguardian/app/engine/AdSdkSignatures.java")
repo = text("app/src/main/java/org/adguardian/app/engine/RuleRepository.java")
ad_type = text("app/src/main/java/org/adguardian/app/engine/AdType.java")
activity = text("app/src/main/java/org/adguardian/app/MainActivity.java")
debug_store = text("app/src/main/java/org/adguardian/app/debug/DebugLogStore.java")
vpn = text("app/src/main/java/org/adguardian/app/network/LocalDnsVpnService.java")
dns_packet = text("app/src/main/java/org/adguardian/app/network/DnsPacket.java")
blocklist = text("app/src/main/java/org/adguardian/app/network/AdDomainBlocklist.java")
ocr = text("app/src/main/java/org/adguardian/app/vision/OcrSkipDetector.java")
yolo = text("app/src/main/java/org/adguardian/app/vision/YoloSkipDetector.java")
yolo_native = text("app/src/main/java/org/adguardian/app/vision/YoloNative.java")
splash = text("app/src/main/java/org/adguardian/app/vision/SplashVisionController.java")
profile = text("app/src/main/java/org/adguardian/app/vision/VisionProfileStore.java")
cmake = text("app/src/main/cpp/CMakeLists.txt")
native_cpp = text("app/src/main/cpp/yolo_jni.cpp")
fetch_vision = text("tools/fetch_vision_deps.sh")
readme = text("README.md")
license_text = text("LICENSE")

all_java = "\n".join(
    p.read_text(encoding="utf-8")
    for p in (ROOT / "app/src/main/java").rglob("*.java")
)

require("android.permission.INTERNET" in manifest, "DNS forwarding requires INTERNET permission")
require("android.permission.ACCESS_NETWORK_STATE" in manifest, "upstream DNS tracking permission is missing")
require("android.permission.BIND_ACCESSIBILITY_SERVICE" in manifest, "Accessibility service binding permission is missing")
require("android.permission.BIND_VPN_SERVICE" in manifest, "VpnService binding permission is missing")
require("android.intent.category.BROWSABLE" in manifest and 'android:scheme="http"' in manifest, "browser discovery query is missing")
require("android.intent.category.HOME" in manifest, "home launcher discovery query is missing")
require("@mipmap/ic_launcher" in manifest, "launcher icon is missing")

require("typeWindowStateChanged|typeWindowContentChanged" in config, "Accessibility event set changed unexpectedly")
require("typeAllMask" not in config and "typeWindowsChanged" not in config, "unnecessary accessibility events enabled")
require("flagReportViewIds" in config, "View ID reporting flag is missing")
require('android:canPerformGestures="true"' in config, "gesture capability is missing")
require('android:canTakeScreenshot="true"' in config, "Android 11 screenshot capability is missing")

require("compileSdk 36" in app_build, "compileSdk must remain 36")
require("targetSdk 36" in app_build, "targetSdk must remain 36")
require("minSdk 30" in app_build, "B4 image pipeline requires Android 11 / API 30")
require("versionName '0.4.2-b4.2'" in app_build, "B4.2 versionName mismatch")
require("versionCode 9" in app_build, "B4.2 versionCode mismatch")
require("abiFilters 'arm64-v8a'" in app_build, "B4 must stay arm64-only to remain below the APK size ceiling")
require("ndkVersion '29.0.14206865'" in app_build, "pinned NDK version is missing")
require("text-recognition-chinese:16.0.1" in app_build, "bundled local Chinese OCR dependency is missing")
require("externalNativeBuild" in app_build and "CMakeLists.txt" in app_build, "ncnn native build is not wired")
require("applicationIdSuffix '.debug'" in app_build, "Debug build must use isolated .debug package ID")
require("signingConfig signingConfigs.development" in app_build, "stable development signing is missing")
require((ROOT / "dev-signing.jks").is_file(), "development signing keystore is missing")

require("actions/upload-artifact@v7" in workflow, "workflow must use upload-artifact v7")
require("archive: false" in workflow, "workflow must upload APK directly")
require("dist/AdGuardian-B4.2.apk" in workflow, "workflow must publish B4.2 APK filename")
require("gradle-version: '8.13'" in workflow, "workflow Gradle version must be 8.13")
require("platforms;android-36" in workflow, "workflow must install Android platform 36")
require("ndk;29.0.14206865" in workflow, "workflow must install pinned NDK")
require("cmake;3.22.1" in workflow, "workflow must install pinned CMake")
require("fetch_vision_deps.sh" in workflow, "workflow must fetch pinned YOLO/ncnn dependencies")
require("52428800" in workflow, "50 MiB APK ceiling is missing")
require("cache-disabled: true" in workflow, "test workflow must disable Gradle cache while diagnosing CI")
require("dist/ci-build.log" in workflow, "integrated build log capture is missing")
require(":app:assembleDebug" in workflow, "debug APK build task is missing")
require(":app:lintDebug" in workflow and "continue-on-error: true" in workflow, "lint must be diagnostic-only in test builds")
require("Verify uploaded B4.2 source" in workflow, "workflow must verify the uploaded B4.2 source before building")
require("Bootstrap Android and build Debug APK" in workflow, "workflow must capture bootstrap and build failures together")

require("info.packageNames = null" in service, "service must not use a per-app allowlist")
require("CONTENT_EVENT_DEBOUNCE_MS" in service, "content-event debounce is missing")
require("SplashVisionController" in service and "beginSession" in service, "visual pipeline is not wired into service")
require("observeAccessibilityEvidence" in service and "noteL1Action" in service, "downgraded apps cannot recover from Accessibility evidence")
require("visionController.endSession" in service, "foreground-boundary vision cancellation is missing")
require("rootPackageSequence" in service, "active-window package ownership guard is missing")
require("SERVICE_EXCEPTION" in service, "debug service exception logging is missing")

require("ACTION_VIEW" in policy and "CATEGORY_BROWSABLE" in policy, "dynamic browser discovery is missing")
require("GET_RESOLVED_FILTER" in policy and "countDataAuthorities" in policy, "deep-link false-browser guard is missing")
require("com.android.chrome" in policy and "org.mozilla.firefox" in policy, "static browser fallback is incomplete")
require("com.UCMobile" in policy and "com.quark.browser" in policy, "UC/Quark splash-ad exceptions are missing")
require("CATEGORY_HOME" in policy, "home package discovery is missing")

require("GenericAdEngine" in rule_engine, "L1 generic accessibility engine is not wired")
require("GENERIC_BLOCKED" in rule_engine, "L1 success logging is missing")
require("MAX_TREE_NODES = 180" in generic, "L1 generic tree traversal must stay bounded")
require("TREE_SCAN_INTERVAL_MS = 360L" in generic, "L1 full-tree throttle is missing")
require("AdSdkSignatures" in generic, "shared SDK signatures are not wired")
require("dispatchGesture" in generic, "L1 high-confidence gesture fallback is missing")

require("FIRST_SCAN_DELAY_MS = 500L" in splash, "500ms splash grace period is missing")
require("POLL_INTERVAL_MS = 750L" in splash, "750ms splash polling cadence is missing")
require("CORE_WINDOW_MS = 8_000L" in splash, "8s core splash window is missing")
require("MAX_WINDOW_MS = 45_000L" in splash, "45s late-ad window is missing")
require("SCREENSHOT_TIMEOUT_MS = 2_000L" in splash, "2s screenshot timeout is missing")
require("MAX_TAPS_PER_SESSION = 3" in splash, "per-session tap cap is missing")
require("OcrSkipDetector" in splash and "YoloSkipDetector" in splash, "L2/L3 cascade is incomplete")
require("evidenceTracker" in splash and "if (evidence" in splash, "YOLO ad-evidence gate is missing")
require("ownsActiveWindow" in splash, "image-layer active-window ownership guard is missing")
require("\"L2_OCR\"" in splash and "\"L3_YOLO\"" in splash, "L2/L3 diagnostics are missing")
require("YOLO_WARMUP_DELAY_MS" in splash, "delayed YOLO warm-up is missing")

require("ChineseTextRecognizerOptions" in ocr, "Chinese OCR is not configured")
require("ocr-explicit-ad-action" in ocr and "ocr-skip" in ocr, "OCR target classes are incomplete")
require("area * 3L >= screenArea" in ocr, "OCR large-node false-positive guard is missing")
require("looksLikeOnboarding" in ocr, "OCR onboarding false-positive guard is missing")
require("corner && upper" in ocr, "plain OCR skip must stay corner-restricted")

require("CONFIDENCE_THRESHOLD = 0.55f" in yolo, "YOLO confidence threshold changed unexpectedly")
require("INPUT_SIZE = 640" in yolo and "ANCHORS = 8400" in yolo, "YOLO model geometry mismatch")
require("nativeInfer" in yolo and "YoloNative" in yolo, "YOLO JNI bridge is missing")
require("System.loadLibrary(\"yolo_jni\")" in yolo_native, "YOLO native library loader is missing")
require("find_package(ncnn REQUIRED)" in cmake, "ncnn is not linked")
require("find_library(android_lib android REQUIRED)" in cmake, "Android native library lookup is missing")
require("find_library(jnigraphics_lib jnigraphics REQUIRED)" in cmake, "jnigraphics native library lookup is missing")
require("max-page-size=16384" in cmake, "16KiB native page alignment is missing")
require("use_vulkan_compute" in native_cpp and "num_threads = 2" in native_cpp, "Vulkan/low-thread CPU inference policy is missing")
require("ncnn::Mat::from_android_bitmap" in native_cpp, "native bitmap inference path is missing")
require("ncnn::get_gpu_count()" in native_cpp, "ncnn Vulkan lazy initialization is missing")
require("ncnn::destroy_gpu_instance()" in native_cpp, "ncnn Vulkan lifecycle release is missing")
require("madeye/ad-skipper" in fetch_vision and "MODEL_TAG=\"v1.3\"" in fetch_vision, "YOLO model source/tag is not pinned")
require("NCNN_VERSION=\"20260526\"" in fetch_vision and "android-vulkan.zip" in fetch_vision, "ncnn prebuilt version is not pinned")
require("26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804" in fetch_vision, "ncnn release checksum is missing")

require("DOWNGRADE_AFTER_NO_EVIDENCE_SESSIONS = 15" in profile, "15-session no-ad downgrade is missing")
require("FULL_PROBE_INTERVAL_WHEN_DOWNGRADED = 5" in profile, "periodic full re-probe is missing")
require("markEvidence" in profile, "SDK/L1 evidence must immediately clear the no-ad downgrade")
require("getLongVersionCode" in profile, "app-update reset is missing")

require("com.bytedance.sdk.openadsdk" in sdk_signatures, "Pangle SDK signature is missing")
require("com.qq.e.ads" in sdk_signatures, "GDT SDK signature is missing")
require("com.kwad.sdk" in sdk_signatures, "Kuaishou SDK signature is missing")
require("com.baidu.mobads" in sdk_signatures, "Baidu MobAds signature is missing")
require("com.mbridge.msdk" in sdk_signatures, "Mintegral signature is missing")
require("com.anythink" in sdk_signatures, "TopOn signature is missing")
require("com.applovin" in sdk_signatures, "AppLovin signature is missing")
require("com.unity3d.services.ads" in sdk_signatures, "Unity Ads signature is missing")
require("com.google.android.gms.ads" in sdk_signatures, "Google Mobile Ads signature is missing")

require("NETWORK(\"network\", \"网络广告请求\")" in ad_type, "network ad category is missing")
require("VpnService.prepare" in activity, "VPN permission flow is missing")
require("DNS_BLOCKED" in vpn and "DNS_ADLIKE_NOT_BLOCKED" in vpn, "DNS diagnostics are incomplete")
require("addRoute(VPN_DNS_ADDRESS, 32)" in vpn, "VPN must route only local DNS endpoint")
require("protect(socket)" in vpn, "upstream DNS socket must bypass VPN")
require("AdDomainBlocklist.match" in vpn, "DNS blocklist is not wired")
require("buildNxDomain" in dns_packet and "buildIpv4UdpResponse" in dns_packet, "local DNS response builder is incomplete")
require("SUFFIX_SET" in blocklist, "DNS domain matching must use a suffix set")

require("DebugLogStore.read" in activity and "复制日志" in activity and "清空日志" in activity, "test log UI is incomplete")
require("L2 本地中文 OCR" in activity and "L3 本地 YOLO11n" in activity, "B4 layered pipeline is not shown in UI")
require("MAX_BYTES = 512L * 1024L" in debug_store, "debug log cap is missing")
require("com.autonavi.minimap" in repo and "com.baidu.BaiduMap" in repo and "com.tencent.map" in repo, "map priority fast paths are missing")
require("com.xiachufang" not in repo, "B4 must not depend on a Xiachufang-specific rule")

require("GNU GENERAL PUBLIC LICENSE" in license_text and "Version 3" in license_text, "GPLv3 license text is missing")
require("GPL-3.0-or-later" in readme, "README must declare GPL-3.0-or-later")

for folder in ("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"):
    require((ROOT / f"app/src/main/res/{folder}/ic_launcher.png").is_file(), f"launcher icon missing in {folder}")

for token in ("Firebase", "Crashlytics", "Analytics", "com.google.firebase", "Thread.sleep(", "while (true)", "while(true)"):
    require(token not in all_java, f"forbidden runtime pattern found: {token}")

for token in ("llama.cpp", "InternVL", "gguf", "Vlm", "VLM"):
    require(token not in (all_java + cmake + native_cpp + app_build), f"oversized VLM stack must not enter the APK: {token}")

unsafe_text_values = re.findall(r'AppRule\.Mode\.(?:EXACT|CONTAINS),\s*\n\s*"([^"]+)"', repo)
for value in unsafe_text_values:
    require(value not in {"关闭", "取消", "确定", "允许", "继续", "稍后"}, f"unsafe generic text rule found: {value}")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)

print("AdGuardian B4.2 static project checks passed")
print("APK ceiling: 50 MiB")
print("Pipeline: L1 Accessibility -> L2 OCR -> L3 YOLO")
