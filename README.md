# MeteorSnap

MeteorSnap 是一个 Android 原生 Kotlin 示例项目：用户可以选择手机内置摄像头或通过 OTG 连接的
UVC 星光级 USB 摄像头，持续进行 OpenCV 帧差检测；连续三个分析样本都出现显著变化时，保存
触发前后各约 3 秒的 H.264/MP4 视频。

## 工程配置

- minSdk 23（Android 6.0），target/compile SDK 36
- Kotlin + Coroutines/Flow
- `com.herohan:UVCAndroid:1.0.13`
- OpenCV Android AAR 4.13.0
- MediaCodec 硬件 AVC 编码 + MediaMuxer
- Foreground Service + PARTIAL_WAKE_LOCK + WorkManager 健康检查

## 核心数据流

```text
手机 Camera2 YUV / UVC NV21（1080p，优先 30fps）
              │ stride-safe 归一化 / 立即复制 JNI Buffer
              ▼
       有界 Channel（最多 2 帧）
              │ 单一、有序 IO consumer
       ┌──────┴──────────────────┐
       ▼                         ▼
MediaCodec 连续硬编          OpenCV 分析
       │                   320×240 / 中央 70%
       ▼                   每 3 帧一次（高温每 10 帧）
5 秒 H.264 环形缓存                │
       │                         │ 连续 3 次命中
       └──────────────┬──────────┘
                      ▼
          立即冻结前 3 秒 + 收集后 3 秒
                      ▼
          MediaMuxer → Pictures/MeteorSnap
```

1080p NV21 原始帧约 2.97 MiB，五秒 30fps 约 445 MiB，因此项目不会把原始 Mat/NV21
放入长期环形缓存。缓存的是硬编码 H.264 access unit，内存通常只有数 MiB。触发瞬间立即固定
前录段，后续再单独收集三秒，避免“等待三秒后再查五秒滚动缓存”丢掉最早一秒。

## 运行

1. 用 Android Studio 打开本目录 `new/`，安装 Android SDK 36；IDE 会按本机 SDK 位置生成
   不纳入版本控制的 `local.properties`。
2. 在主页面选择“手机摄像头”或“外挂摄像头”。手机模式优先使用后置主摄，不需要连接 OTG。
3. 外挂模式需使用支持 USB Host/OTG 的 Android 6.0+ 设备，插入 UVC 摄像头并从列表中选择它。
4. 点击“开始监测”，依次允许 CAMERA、通知（Android 13+）和旧版存储（Android 6～9）；
   外挂模式还需确认系统 USB 授权。
5. 成功后首页显示摄像头实时画面，通知栏显示“流星监测中”。预览复用现有 NV21 帧，最大
   640×360、约 8fps（设备过热时约 4fps）；退出页面后停止预览转换，但后台检测和录像继续。
6. 输出文件位于 `Pictures/MeteorSnap/`。

命令行构建可执行 `gradlew.bat :app:assembleDebug`。首次运行会下载 Gradle 8.13；该版本是
Android Gradle Plugin 8.12 的官方匹配版本。调试 APK 输出在
`app/build/outputs/apk/debug/app-debug.apk`。

USB 授权不是 Android dangerous permission，而且物理拔出后授权会失效；重新插入时需要再次确认。
Android 10+ 通过 MediaStore 保存本应用创建的视频，不需要 `MANAGE_EXTERNAL_STORAGE` 或
`READ_MEDIA_VIDEO`。

## 后台与省电说明

- 前台服务必须由可见 Activity 中的用户操作启动；Android 12+ 通常不允许 Worker 在后台启动它，
  Android 14+ 还会即时校验 camera 类型的 while-in-use 权限。
- 15 分钟 Worker 只检查流水线心跳。异常时发布“点击恢复”通知，由用户返回页面后重新授权并启动。
- 默认启用需求指定的“5 分钟无运动释放 WakeLock”。释放后设备若进入深度休眠，可能无法及时收到
  下一帧，因此 UI 明确提示可能漏拍；追求可靠性时应关闭该开关，并把应用加入电池优化白名单。
- 普通应用不能可靠读取 CPU 核心温度。本项目使用电池温度作为 45°C 阈值代理，并结合 Android 10+
  系统 Thermal Status；45°C/42°C 带迟滞，避免检测频率反复抖动。

## 设备兼容性

- 手机模式通过 Camera2/ImageReader 获取 YUV_420_888，优先后置、1920×1080@30fps；不支持时按
  帧时长、16:9 比例和不超过 1080p 的规格安全降级，并严格处理 Plane 的 crop/row/pixel stride。
- 外挂模式优先协商 MJPEG 1920×1080@30fps；设备不支持时按可用 MJPEG 规格降级。
- UVC 曝光、增益、白平衡能力由设备固件决定，设置前均检测支持范围并 clamp。
- 编码器使用 `COLOR_FormatYUV420Flexible` 与 `getInputImage()`，严格按 Y/U/V plane 的
  rowStride/pixelStride 写入。极少数厂商编码器虽声明 Flexible 却不提供输入 Image，此时服务会报告
  编码器错误；生产版可再加入 NV21→NV12 厂商格式或 EGL input Surface 回退路径。

## 主要文件

- `CameraModels.kt`：手机/UVC 共用的来源、规格和 NV21 帧模型
- `InternalCameraManager.kt`：Camera2 后置优先、YUV_420_888→NV21、有限重连
- `UVCCameraManager.kt`：UVC 发现、授权、连接、参数、NV21 回调、断线重连
- `RingBuffer.kt`：基于 ConcurrentLinkedDeque 的线程安全时间窗缓存
- `FrameAnalyzer.kt`：OpenCV 帧差、ROI、跳帧和连续三次确认
- `HardwareH264Encoder.kt`：持续硬件 AVC 编码
- `MeteorClipWriter.kt`：MediaMuxer + MediaStore/旧版公共目录导出
- `MeteorDetectService.kt`：前台服务与完整流水线编排
- `Nv21PreviewView.kt`：复用缓冲的低分辨率 NV21 彩色实时预览
- `MainActivity.kt`：手机/外挂来源选择、实时预览、权限、启停和系统设置引导

## 相关官方资料

- [UVCAndroid 维护分支](https://github.com/shiyinghan/UVCAndroid)
- [Camera2 采集会话](https://developer.android.com/media/camera/camera2/capture-sessions-requests)
- [OpenCV Android AAR 使用方式](https://opencv.org/opencv4android-usage-models/)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 共享媒体存储](https://developer.android.com/training/data-storage/shared/media)
