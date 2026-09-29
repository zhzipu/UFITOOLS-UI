# UFITOOLS-UI

UFI U60 Pro（中兴 MU5250）随身 WiFi 的 **Android 原生控制器**。

官方只提供 Web 控制台（`http://设备IP:2333`），在手机上操作体验很差。本项目用 Kotlin + Jetpack Compose 重写了整套控制界面，并额外实现了官方 Web 端**没有**的功能：桌面小组件、设备拉黑、电源三态识别。

## 功能

| 模块 | 说明 |
|---|---|
| **仪表盘** | 型号、运营商（品牌图标）、信号格数、实时上下行速率、今日/本月流量、电池三态、CPU/内存、存储空间、开机时长 |
| **信号** | 网络制式/频段、RSRP/SINR、5G 与 4G 分项、小区信息、运营商 |
| **蜂窝网络模式** | 6 档优先级切换（5G/4G/3G、5G NSA、5G SA、4G/3G、仅4G、仅3G），写入后回读校验 |
| **设备信息** | 仪表盘右上角「i」按钮弹出；长按任意项即可复制 |
| **短信** | 列表、发送、删除、标记已读 |
| **连接管理** | 在线终端列表、一键拉黑设备、自定义终端图标 |
| **外观** | 5 套配色预设 + 明暗模式 |
| **设置** | WiFi 开关、数据漫游、短信转发、**黑名单**、后台密码、重启/关机、AT 终端 |
| **猫猫面板** | 原生 UI 直连 mihomo external-controller：模式切换、策略组选节点、单点/整组测速、订阅流量、活动连接管理 |
| **桌面小组件** | 4×2 / 4×1 / 2×1 三种尺寸，RemoteViews 实现 |

## 猫猫控制面板

设备上跑 mihomo 内核时，底栏的 **猫猫** 页可直接管理它——**原生 Compose 实现，不内嵌 WebView**。

**无需任何配置**：地址固定为 `http://192.168.0.1:9090`（猫猫内核就在设备上，填别的没有意义），`secret` 进入页面时**自动从设备读取**（通过 `/api/run_shell` 解析设备上的 `mihomo/config.yaml`，认证走 `Authorization: Bearer <secret>`），读到后本地缓存，下次直接复用。

第一次探活若失败会再读一次配置重试，覆盖「内核刚重启、secret 变了」的情形。仍然连不上就说明设备上**没有在跑的猫猫服务**，页面会直接提示「未安装/运行猫猫服务」。

> 与 [zashboard](https://github.com/Zephyruso/zashboard) 的关系：zashboard 是这些 REST 接口的 Web 前端，本项目直接对接同一套接口，因此功能分区（概览 / 节点 / 连接）与它一致，但不受其前端版本变动影响，也无需额外部署。

这一页走的是**独立通道**，与设备自研的 `/api/`（2333 端口）无关，所以设备连接状态与面板连接状态互不影响。

## 技术栈

- Kotlin 2.0.21 / Jetpack Compose（BOM 2024.10.01）/ Material 3
- AGP 8.5.2，compileSdk 34，minSdk 26
- OkHttp 4.12 + Gson 2.11，协程
- 架构：单 `MainViewModel` + `StateFlow` 式 Compose 状态 + Navigation Compose

无第三方 UI 库、无注入框架，依赖精简到 12 个。

## 三条数据通道

设备对同一份设置往往有多个入口，可靠性和能力各不相同。本项目按 **ubus → goform → sysfs** 的优先级组合使用：

| 通道 | 能力 | 局限 |
|---|---|---|
| **ubus**（经 `/api/run_shell` 调 `/bin/ubus`） | 写入**可回读校验**，字段最权威 | 依赖固件带 `ubus` |
| **goform**（`/goform/goform_*cmd_process`） | 字段覆盖最全 | **投递即成功**：不存在的 goformId、非法值、错 AD 一律回 `success`，响应码零信息量 |
| **sysfs**（`/sys/class/power_supply/`） | 内核节点，电源状态最准 | 需 shell 权限 |

## 构建

需要 JDK 17 与 Android SDK（compileSdk 34）。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建
./gradlew assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次在设备上使用：打开 App → 填写设备地址（默认 `192.168.0.1:2333`）→ 填写控制台口令 → 连接。

## 逆向得到的设备契约

以下结论均来自对 U60 Pro（固件 `BD_CNMU5250V1.0.0B31`）的实测抓包与字段比对，**部分字段与直觉相反**，记录下来供同机型开发者参考。

### 认证

- `authorization` 头 = `SHA256(控制台口令)` **小写** hex
- `kano-t` = 毫秒时间戳，`kano-sign` = `HMAC-MD5` 混合签名（见 `Crypto.kt`）
- `X-Device-Token` = 从免鉴权接口 `/api/need_token` 获取
- **goform 登录口令反而用大写 hex**：`password = SHA256(SHA256(后台密码) + LD)`，小写必然失败（回 `result:1` 且不下发 cookie）。两处大小写不能混用。

### 容易踩坑的字段

| 项目 | 结论 |
|---|---|
| `network_signalbar` | **恒为 `"5"`，是死字段**，不可当信号格数用。本项目按 RSRP 推算（阈值 -80/-90/-100/-110） |
| `battery_charging` | **恒为 `"0"`**，插着充电器也是 0 |
| `realtime_rx_thrpt` | **不存在**。实时速率是 `real_rx_speed` / `real_tx_speed`（B/s） |
| `battery` | **不存在**。电量是 `battery_value` / `battery_vol_percent` |
| `rssi` | 恒为 0，别拿它当信号强度 |
| `client_ip` | 返回的是**发起请求这一端**的 IP，不是设备地址。设备地址用 `lan_ipaddr` |
| `cpu_temp` | 单位**毫摄氏度**（56500 → 56.5℃） |
| 电源三态 | goform 与内核节点都不够准，权威来源是 ubus `zwrt_bsp.charger list` 的 `direct_power_supply_mode` |

### 设备不支持的功能

性能模式、唤醒锁、指示灯开关——固件无对应字段，官方 Web 端也没有入口，本项目的界面里已移除。

### 桌面小组件

- RemoteViews 只能 inflate 白名单控件，分隔线/信号柱都用 `ImageView` + `setBackgroundColor`（`setColorFilter` 不在反射白名单里）
- 系统 `updatePeriodMillis` 硬下限是 30 分钟，再小也会被系统拉回去
- App 在前台轮询时主动推送数据，规避后台刷新限制

## 已知限制

- 仅针对 U60 Pro / MU5250 验证，其他中兴机型字段可能不同
- 拉黑设备走 goform `setDeviceAccessControlList`（设备端接入控制黑名单）。两点已知行为：
  - 设备会**自行重排** `BlackMacList` 顺序，且**不保证保留名称**（中文备注实测会被丢弃），故界面上以 MAC 为准
  - `AclMode` 是设备**自算的状态**（黑名单为空为 `0`、有内容为 `1`），App 只做原样回写，不擅自改值
- 桌面小组件的后台刷新最慢 30 分钟一次

## 致谢

- [33333s/zwrt-datad](https://github.com/33333s/zwrt-datad) —— 中兴 ARM64 5G 路由的统一数据/控制服务，为 ubus 通道与电源三态提供了关键线索
- [xiatian410/UFITOOLS-U60Pro-Widget](https://github.com/xiatian410/UFITOOLS-U60Pro-Widget) —— 本项目的配色体系与视觉规格参考了该作品

## 许可证

[MIT](LICENSE)
