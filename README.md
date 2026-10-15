# 谛听 (DITING) 精简版

谛听（DITING）是一款专注于本地 DNS 解析加速、智能调度优化与网络流量过滤的开源工具。本分支为**精简版**：仅保留 Android 普通模式（Go 用户态全隧道），完整删除极速模式、独立服务器模式与其他平台内容，聚焦广告拦截、域名管控与应用级网络隔离。全链路在设备本地闭环运行，无任何远程数据上传与隐私追踪。

## 核心特性

### DNS 解析与上游调度
- **多协议支持**：标准 DNS（UDP/TCP 53）、DNS-over-HTTPS（DoH）、DNS-over-TLS（DoT）。
- **多策略调度**：单一服务、智能优选（EWMA 延迟预测与成功率考量）、最快响应（并发竞速）、依次尝试（备援容灾）。
- **服务商管理**：内置阿里、腾讯、Cloudflare、Google、DNSPod 等主流公共 DNS，支持自定义节点。
- **Bootstrap 引导**：内置与自定义 Bootstrap IP 解析加密上游域名，避免递归死锁。

### 智能缓存与容灾
- 并发安全分片 LRU 缓存，支持跟随 TTL、平衡、高命中等策略。
- Stale-While-Revalidate 乐观容灾与 Singleflight 并发合并防击穿。

### 规则引擎
- 域名黑白名单：兼容 AdGuard 语法、通配符与正则；Trie 前缀树与 Bloom 过滤器加速。
- 规则订阅与自动更新：支持 AdGuard 格式远程订阅、分组、镜像源加速与定时拉取。
- 静态覆写：IPv4 / IPv6 地址覆写与 CNAME 重定向。
- 多样化阻断响应：零地址、NXDOMAIN、NODATA、REFUSED。

### 应用级网络管控（应用独立规则）
- **默认拦截全部外联**：开启后该应用进入网络层默认拒绝状态，仅能访问放行白名单中的域名，防止后台偷跑流量。
- **放行白名单**：统一管理所有放行域名——包括该应用在 DNS 白名单中的全部规则（含订阅导入的 `@@||域名^$app=包名` 规则）以及历史手动添加的放行域名。
- **DNS 专属黑白名单**：按应用单独配置 DNS 阶段的屏蔽与放行规则，支持通配符。
- **排除应用**：绕过谛听直连系统 DNS；**禁止联网应用**：丢弃指定应用的全部外联。

### 其他能力
- 可选 HTTPS 流量检查（本地 CA，仅解密勾选应用，支持证书绑定旁路与 QUIC/H3 回退）。
- 出站代理联动（SOCKS5 / HTTP CONNECT）。
- 悬浮窗网速、按 UID 流量统计、Ping / Traceroute / DNS 诊断、快捷设置磁贴。
- Material 3 动态取色、深浅主题、自定义背景、配置导入导出。

## 构建

### 本地构建
1. 安装 JDK 25（项目固定 toolchainVersion=25，禁用工具链自动下载）与 Android SDK。
2. 在 `Android` 目录执行：
   ```bat
   gradlew.bat assembleDebug
   ```
   或运行 `build_apk.bat` 交互式构建与安装。
3. 产物位于 `Android/app/build/outputs/apk/debug/app-debug.apk`。

> Go 引擎已预编译为 `Android/app/libs/tunnel.aar`（arm64-v8a），构建无需本地 Go 工具链。

### 云端构建（GitHub Actions）
推送代码到 `main` / `list` 分支后，Actions 自动执行 `.github/workflows/android-build.yml`，编译 Debug APK 并以 artifact 形式提供下载。

## 规则订阅示例

在「设置 → 规则控制 → 规则订阅」中添加 AdGuard 格式订阅源即可生效，例如：

- 行路知遥规则（含应用级放行/拦截规则）
- HyperADRules 广告规则 / 恶意规则 / 白名单
- 轻量广告拦截规则、秋风广告规则、Host10007 等

订阅中的 `||*^$all,app=包名` 会触发对应应用的「默认拦截全部外联」，`@@||域名^$app=包名` 会进入该应用的放行白名单。

## 许可

沿用上游 GPL-3.0 许可。仅用于屏蔽与过滤有害域名、净化网络环境，请遵守相关法律法规。
