# FFCompress

安卓原生视频压缩工具，双压缩引擎 + 实时资源监控，让你完全掌控压缩任务的 CPU/内存占用。

## 功能

- **双压缩引擎**
  - 🚀 硬件编码（Media3 Transformer）：低 CPU 占用、省电、速度快，日常首选
  - 🎛️ FFmpeg 软编（libx264）：压缩率最优，支持精细参数控制
- **资源控制**（软编引擎）
  - CPU 档位：1-8 线程可调（对应 FFmpeg `-threads`），压缩再久也不会把手机跑满
  - 编码速度档位：极快 / 均衡 / 高压缩（对应 x264 `preset`）
- **实时资源监控面板**：本机 CPU、压缩进程 CPU、App 内存、系统可用内存、磁盘写入速度（每秒采样）
- **压缩选项**：分辨率（原样/1080P/720P/480P）、码率（自动/4M/2M/1M）
- 前台服务 + 通知栏进度，随时取消

## 构建

要求：JDK 17、Android SDK（compileSdk 35）。

```bash
git clone https://github.com/ArielRan/FFCompress.git
cd FFCompress
./gradlew assembleDebug        # Windows 用 gradlew.bat assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

## 技术栈

- Kotlin + Jetpack Compose
- 硬编：[androidx.media3 Transformer](https://developer.android.com/media/media3/transformer)
- 软编：[FFmpegKit（社区维护版）](https://github.com/ffmpegkit-maintained/ffmpeg) + libx264
- 资源监控：`/proc/self/stat`、`/proc/self/io`、`ActivityManager`

## 许可证

[GPL-3.0](LICENSE)。本项目链接了 GPL 版 FFmpegKit（libx264），故整体以 GPL-3.0 发布。

## 致谢

- [FFShare](https://github.com/caydey/ffshare) —— 本项目的参考与灵感来源
- [FFmpegKit maintained fork](https://github.com/ffmpegkit-maintained/ffmpeg) —— 原版停维后仍在维护的社区分支
