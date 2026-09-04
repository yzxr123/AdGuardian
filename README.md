# AdGuardian

AdGuardian 是一个单 APK 纯本地 Android 广告权益保护项目

许可证  GPL-3.0-or-later

## B4.3 核心变化

B4.3 延续 B4 的通用分层识别架构 并修正原生视觉构建链

主链路改为

`L1 Accessibility -> L2 本地 OCR -> L3 本地 YOLO -> 点击`

这条链路用于覆盖大量没有专门适配规则的第三方 App

高德地图 百度地图 腾讯地图的少量专项规则继续保留 但只作为高置信快速路径和导航场景补强

## L1 Accessibility

- 监听全部第三方 App 但排除系统 App和普通浏览器
- 只监听 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOW_CONTENT_CHANGED`
- 普通节点先做快速文本和 Resource ID 查询
- 完整树扫描最短间隔 360 ms
- 单次最多访问 180 个节点
- 广告 SDK 共性标记集中在 `AdSdkSignatures`
- 优先 `ACTION_CLICK`
- 只有高置信目标普通点击失败才使用 `dispatchGesture`

## L2 本地 OCR

Android 11 及以上使用 Accessibility screenshot API 获取当前活动窗口截图

- App 会话开始后先等待 500 ms 避开 OEM 启动快照
- 前 8 秒每 750 ms 主动探测
- OCR 能处理 Flutter Unity 游戏或自绘 UI 中无无障碍节点的跳过按钮
- 识别 `跳过` `跳過` `Skip` `关闭广告` `跳过广告` 等
- 普通 `跳过` 只允许屏幕上半区域角落的小目标
- 明确 `关闭广告` 类目标才允许更宽的位置范围
- 页面出现新手引导类文本时抑制普通 Skip 点击
- 任何手势执行前再次确认活动窗口仍属于原 App

## L3 本地 YOLO

B4 内置约 5 MB 的 YOLO11n 跳过按钮检测模型

- ncnn 推理
- arm64-v8a
- Vulkan GPU 优先
- GPU 不可用时 CPU NEON 回退
- CPU 线程限制为 2
- YOLO 只在 L1 和 L2 未命中后运行
- YOLO 必须先获得广告证据才允许点击

广告证据包括

- OCR 检测到广告 推广 赞助等标识
- 同一会话倒计时跨轮询递减
- Activity 或 View ID 命中已知广告 SDK 指纹

因此不会仅因为视觉上存在一个 X 或类似按钮就直接点击

## 主动开屏轮询

部分开屏广告不会产生新的 Accessibility 事件

所以 B4 不再完全依赖事件触发

- 第一次探测延迟 500 ms
- 核心阶段 750 ms 一次
- 核心窗口 8 秒
- 最长观察窗口 45 秒
- 8 秒后只在存在证据 小型开屏树或低频晚广告探测时截图
- 单个会话最多执行 3 次点击
- 截图回调超过 2 秒直接放弃

## 无广告 App 自动降级

为了避免长期对根本没有开屏广告的 App 截图

- 连续 15 个启动会话没有广告证据后降为 L1-only
- 降级后每第 5 次会话重新进行一次完整探测
- 一旦出现广告证据立即恢复完整探测
- App 更新后自动重置学习状态

## 浏览器防误点

普通浏览器网页中存在大量 Skip 关闭和广告样式内容

因此 B4 在事件入口排除常见浏览器并动态查询设备浏览器

静态排除包括 Chrome Firefox Edge Samsung Internet 小米 OPPO vivo 华为 QQ 浏览器 百度浏览器等

UC 和夸克自身可能存在启动广告所以保留在检测链路中

## 本机 DNS 层

B3 的本机 DNS 广告域名过滤继续保留

- Android VpnService
- 不需要 Root
- 不需要 Shizuku
- 不连接 AdGuardian 自建服务器
- 不做 HTTPS MITM
- VPN 只路由虚拟 DNS 地址 `10.113.0.1/32`
- 高置信广告域名本机返回 NXDOMAIN
- 允许的 DNS 请求使用受保护 Socket 发往当前网络上游 DNS
- 当前只声明 IPv4 UDP DNS 支持

## 日志

Debug APK 仅在本机保存最多 512 KiB 日志

重点代码

- `GENERIC_BLOCKED`
- `L2_OCR_BLOCKED`
- `L3_YOLO_BLOCKED`
- `AD_EVIDENCE`
- `L2_SCREENSHOT_FAILED`
- `L2_SCREENSHOT_TIMEOUT`
- `L2_OCR_ERROR`
- `L3_YOLO_ERROR`
- `*_ACTION_CANCELLED`
- `ROOT_PACKAGE_MISMATCH`
- `DNS_BLOCKED`
- `DNS_ADLIKE_NOT_BLOCKED`

日志不会上传

## 构建

GitHub Actions 会自动下载固定版本的本地视觉依赖

- YOLO 模型来源固定为 `madeye/ad-skipper` v1.3
- ncnn 固定为 20260526 Android Vulkan
- NDK 固定 29.0.14206865 与 ncnn 20260526 官方预编译版本保持一致
- CMake 固定 3.22.1
- AGP 8.13.2
- Gradle 8.13
- JDK 17

成功后生成

`AdGuardian-B4.3.apk`

APK 硬限制现在是 50 MiB

超过 50 MiB GitHub 构建直接失败

## B4.3 构建修复

- ncnn 20260526 官方 Android 预编译库使用 NDK r29 因此工程同步到 `29.0.14206865`
- Debug APK 先执行 `assembleDebug` 生成 安装包不再被 lint 阻断
- lint 继续执行并保存结果 但测试构建阶段仅作为诊断
- Gradle 构建完整输出写入 `dist/assemble-debug.log`
- 如果构建失败 GitHub Summary 自动显示最后 240 行错误并上传完整日志
- 测试阶段关闭 Gradle Action 缓存 避免无关缓存警告干扰定位

## 当前未完成

- 广告跨 App 跳转的真正前置阻断
- 一体化传感器高权限桥
- 摇一摇和扭一扭广告传感器限制
- DNS over TCP
- Private DNS DoT 专项处理

这些模块不会在 UI 中冒充已经实现

- AndroidX 已启用，因为 ML Kit 中文 OCR 会传递依赖 AndroidX；Jetifier 保持关闭，避免无意义转换。
