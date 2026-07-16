# G 航迹（GFlight）

[![Android APK](https://github.com/amwangfan/gsensor/actions/workflows/android.yml/badge.svg)](https://github.com/amwangfan/gsensor/actions/workflows/android.yml)

面向民航旅行的 Android 加速度记录器。应用由用户主动启动，以前台服务和常驻通知在后台持续记录，输出 CSV 到系统共享目录 `Download/g/`。

> 当前是可安装测试版工程（v0.1.1），不是经校准的航空仪表，也不能用于飞行安全判断。

## 已实现

- 两板块主页：当前记录、已保存的记录。
- 自适应采集：
  - 正常：传感器请求 10 Hz，CSV 每秒 1 条；
  - 在约 ±0.06 g 容差内持续稳定 8 秒：传感器请求 5 Hz，CSV 每 5 秒 1 条；
  - 偶发扰动会立即补记但不重置稳定计时；连续 3 个采样点越界才进入高精度；
  - 检测到持续变化或约 0.15 g 以上突变：传感器请求 50 Hz，CSV 最高 10 Hz，并保持 10 秒。
- 后台前台服务、常驻低优先级通知、通知内“停止并保存”。
- 息屏可靠模式：优先使用唤醒型加速度计；设备没有该能力时使用部分唤醒锁。关闭该选项会更省电，但部分设备息屏深度休眠时可能产生记录空档。
- 设备三轴以及东—北—天（ENU）世界坐标；世界坐标由旋转矢量传感器实时换算。
- 可选 GNSS：保存经纬度、速度、海拔、水平/垂直精度，以及低通后的速度变化率。
- 可选气压：保存舱压以及相对气压高度。
- 内置 CSV 列表、系统文件选择器、统计信息、合加速度/天地轴曲线和分享。
- 不需要存储权限；Android 10 及以上通过 MediaStore 写入共享下载目录。

## 构建与安装

最低 Android 10（API 29），目标 Android 16（API 36）。项目使用 Java 17、Android Gradle Plugin 9.3.0 和 Gradle 9.5.0。

### GitHub Actions（推荐）

1. 把工程推送至 GitHub，默认分支命名为 `main`。
2. 打开 **Actions → Android APK → Run workflow**。
3. 构建成功后，在该次运行的 **Artifacts** 下载 `GFlight-debug-apk`。
4. 解压并在手机上安装 `app-debug.apk`；若系统拦截，需要仅对所用文件管理器/浏览器允许“安装未知应用”。

工作流会先运行单元测试与 Android Lint，再构建 debug APK。debug APK 使用 Android 自动生成的调试签名，适合自用测试，不适合商店发布。

### Android Studio

用当前稳定版 Android Studio 打开目录，安装 Android SDK 36 和 Build Tools 36.0.0，然后运行 `app`。命令行已安装 Gradle 9.5.0 时也可执行：

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

## 飞行时建议

1. 起飞前打开应用，选择是否启用 GNSS，点击“开始记录”，确认通知已经出现。
2. 把手机固定在平稳位置。若希望“天地轴 G”更可比较，不要在记录中随意挪动手机。
3. 长航程建议关闭不需要的 GNSS；机身会显著遮挡卫星信号，巡航阶段 GNSS 很可能长期无数据。
4. 对省电要求高时可以关闭“息屏可靠模式”，但务必先在自己的机型上做一次 30 分钟息屏测试，检查 CSV 时间戳是否存在大段空档。
5. 华为/荣耀等系统可能有额外的后台启动和电池优化策略。请允许该应用后台运行；不要在记录期间清理应用或强行停止。

## 数据含义与限制

- `total_g` 是加速度计三轴合成值除以标准重力加速度；手机静止时通常接近 1 g。
- `vertical_g` 是利用旋转矢量换算后的世界坐标“天轴”分量。它能减轻手机摆放角度的影响，但旋转矢量本身仍会受磁场、机身振动和设备算法影响。
- 手机在桌面滑动、旋转、掉落、被手拿起或受到局部振动时，读数都会变化。这些变化不能简单解释为飞机机体过载。
- GNSS 的 5 秒级速度/海拔数据噪声远高于 MEMS 加速度计，应用只把它作为独立辅助列保存，不会强行覆盖加速度计读数。
- 气压高度以记录开始时的舱压为零点；客舱增压变化不等同于飞机真实高度。
- 各手机传感器量程、分辨率、时间戳稳定性和厂商后台策略均不同，必须用目标设备实测。

Android 官方说明：连续型运动传感器在 Android 9 及以后需要在前台或前台服务中获取；传感器不会在屏幕关闭时被系统自动停用，但持续采集会耗电。前台服务在 Android 14 及以后还必须声明用途类型。本项目据此使用用户可见的 `specialUse` 前台服务；启用定位时同时使用 `location` 类型。参见 [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)、[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) 和 [MediaStore shared storage](https://developer.android.com/training/data-storage/shared/media)。

## CSV 主要列

| 列 | 含义 |
|---|---|
| `elapsed_ms`, `utc_epoch_ms` | 单调时钟相对时间与 UTC Unix 毫秒 |
| `mode` | `NORMAL` / `STABLE` / `ACTIVE` |
| `device_*_mps2` | 手机固定坐标三轴 |
| `east_mps2`, `north_mps2`, `up_mps2` | ENU 世界坐标；无旋转矢量时为 `NaN` |
| `total_g`, `vertical_g` | 三轴合成 G 与天地轴 G |
| `pressure_hpa`, `baro_relative_alt_m` | 舱压与相对气压高度 |
| `latitude`, `longitude`, `gps_*` | 可选 GNSS 原始辅助字段 |
| `sensor_accuracy` | Android 传感器精度状态码 |

## 隐私

应用无网络权限、无分析 SDK、无广告。传感器和可选位置数据只写到本机 CSV。分享文件是用户主动触发的系统操作。
