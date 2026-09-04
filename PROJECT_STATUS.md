# AdGuardian B4.3 status

Implemented

- one APK
- GPL-3.0-or-later project source
- local configuration
- no account
- no AdGuardian server
- no telemetry
- approved custom launcher icon
- debug local diagnostics
- Android 11+ arm64 target
- all-third-party Accessibility intake without a per-app allowlist
- system-app exclusion
- browser exclusion with dynamic discovery and static fallback
- UC and Quark browser exceptions
- L1 bounded Accessibility detector
- L2 bundled on-device Chinese OCR detector
- L3 bundled YOLO11n skip-button detector
- ncnn Vulkan inference with CPU fallback
- delayed YOLO warm-up
- 500 ms splash grace period
- 750 ms active splash polling
- 8 s core image-detection window
- 45 s late-ad observation window
- 2 s screenshot timeout
- maximum three taps per app session
- active-window ownership check before gesture injection
- OCR geometry and onboarding false-positive guards
- YOLO ad-evidence gate
- OCR advertisement-label evidence
- decreasing-countdown evidence
- ad-SDK Activity and View ID evidence
- 15-session no-ad downgrade to L1-only
- every-fifth-session full re-probe after downgrade
- app-version reset of vision profile
- centralized shared ad-SDK signatures
- map-specific priority fast paths
- local DNS-only VpnService layer
- 84 high-confidence DNS block suffixes in current test set
- GitHub cloud APK build
- pinned YOLO model and ncnn build dependencies
- NDK r29 aligned with ncnn 20260526 Android prebuilt
- CI assemble and lint separated
- full Gradle failure log exported by CI
- 50 MiB APK build ceiling

Detection order

1. map-specific priority rule when available
2. generic Accessibility fast match
3. bounded Accessibility structure scan
4. local OCR screenshot recognition
5. ad evidence accumulation
6. YOLO only when L1 and OCR miss and ad evidence exists
7. gesture injection after active-window revalidation

Important limitations

- image layers require Android 11 accessibility screenshot API
- B4 is arm64-v8a only to keep the single APK below the size ceiling
- FLAG_SECURE windows cannot provide usable screenshots so only L1 can work there
- ordinary browsers are intentionally excluded to reduce dangerous web-page false taps
- map priority rules are not the support boundary
- app-specific rules are exceptions and fast paths only
- local DNS filtering consumes Android's single user VPN slot
- DNS layer does not perform HTTPS MITM

Not implemented yet

- cross-app jump prevention
- embedded privileged sensor bridge
- shake-ad sensor restriction
- DNS over TCP
- strict Private DNS DoT handling
- per-UID DNS request attribution

- AndroidX 已启用，因为 ML Kit 中文 OCR 会传递依赖 AndroidX；Jetifier 保持关闭，避免无意义转换。
