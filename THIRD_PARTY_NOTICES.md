# Third party references and bundled dependencies

AdGuardian project source is released under GPL-3.0-or-later

## Generic skip architecture reference

madeye/ad-skipper

https://github.com/madeye/ad-skipper

License in that repository  MIT

B4 follows the same proven high-level cascade concept

Accessibility node match -> local OCR -> local YOLO -> gesture

AdGuardian's Java service orchestration safety policy DNS layer UI and project structure are maintained in this repository rather than copying the upstream application wholesale

The YOLO NCNN model files fetched during CI are pinned to the upstream `v1.3` release source

## ncnn

Tencent ncnn

https://github.com/Tencent/ncnn

The GitHub build downloads the pinned 20260526 Android Vulkan prebuilt

ncnn is used only as the local YOLO inference runtime

## OCR dependency

B4 currently uses Google's bundled on-device ML Kit Chinese text recognition artifact

`com.google.mlkit:text-recognition-chinese:16.0.1`

The OCR model is packaged for local inference and does not require AdGuardian servers or Google Play Services at runtime

This library is a binary third-party dependency and is not itself the AdGuardian GPL source code

If the project later requires every binary dependency to also have an open-source implementation the OCR backend should be replaced by an ncnn PaddleOCR or RapidOCR implementation

## Accessibility and rule research

- GKD
  https://github.com/gkd-kit/gkd
- GKD subscription documentation
  https://github.com/gkd-kit/docs
- AIsouler GKD subscription archive
  https://github.com/AIsouler/GKD_subscription
- LiTiaoTiao community rule backups
  https://github.com/xue-mark/BACKUP-LiTiaotiao-Custom-Rules

These references are used to study global selectors app-specific long-tail rules shared SDK identifiers and false-positive patterns

## DNS and local VPN research

- Android VpnService documentation
- PCAPdroid
  https://github.com/emanuele-f/PCAPdroid
- NetGuard
  https://github.com/M66B/NetGuard
- AdAway
  https://github.com/AdAway/AdAway
- DNS66
  https://github.com/julian-klode/dns66
- AdGuard public filter repositories
  https://github.com/AdguardTeam/AdguardFilters

B4 continues to use a manually curated high-confidence DNS subset rather than bundling a large hosts database wholesale

## Later privileged and sensor research

- no-shakingAD
  https://github.com/WeiyePlayer/no-shakingAD
- Kadb
  https://github.com/flyfishxu/Kadb
- Shizuku
  https://github.com/RikkaApps/Shizuku
