# 测试说明

## 本次开源整理

2026-10-19：采用 GPL-3.0-only，整理公开说明和构建资料，不改变接收协议、播放器逻辑、版本号、界面或本地签名私钥。


本次在排除原签名私钥和本机配置的源码副本中复验：Gradle Wrapper 首次下载与校验通过，自动生成本地调试密钥，assembleDebug / lintDebug 通过（0 个错误、10 个警告）；29 项协议检查、10 项格式检查通过。复用了已缓存的 Android 构建依赖，未复验全新机器首次下载全部 Maven 依赖。未重新执行安卓模拟器或真机播放验收。

## 可独立运行的核心检查

依赖 Python 3 和 JDK 17 / 21，无需 Android 模拟器。设置 JAVA_HOME 后，逐个执行：

```sh
python3 tests/test_protocol.py
python3 tests/test_format_probe.py
```

协议测试使用本地回环服务和测试控制器，覆盖 SSDP、XML / SOAP / GENA、动作参数、事件订阅等，历史结果为 29 项通过。会临时使用本机 UDP 1900；若已有服务占用，请在空闲测试环境运行。

格式检查使用合成响应，覆盖 HLS / DASH / MP4 / FLV、误标格式、HTML / JSON 错误页、重定向与有限读取，历史结果为 10 项通过。所有测试令牌均为虚构文本，不是平台凭据。

输出写入 build/，不应提交生成的日志。

## 安卓手工回归

`tests/test_android.py` 保留了开发阶段的 Android 11 / API 30 模拟器回归脚本，历史结果为 22 项通过。它需要专用模拟器、ADB、外部合成 HLS 服务和开发时的目录结构，**并非下载源码后即可直接运行的通用测试**。它没有进入默认核心测试流程，也不能代替真实电视验收。

复测建议：

1. 在目标电视确认设备发现、收到地址、画面与声音。
2. 检查暂停、继续、换视频、停止、返回与退出。
3. 使用有权限的媒体检查 HLS / DASH，及误标成 MP4 的 HLS。
4. 用合成 HTTP 服务检查 503 暂时故障、401 失效地址和错误页，不在公共 Issue 中提供真实令牌。
5. 单独记录连续两小时播放、网络中断恢复、声画同步和 4K / HDR 结果。

## 已有真机反馈

2026-10-15，用户在索尼 XR-85X95EK 上报告 Z视介、小红书、抖音可以投屏。发送设备为安卓平板，具体 App 版本未记录。属于该组合下的用户反馈，不代表所有版本、设备或直播频道都兼容。

## 截图

README 中的等待页为 0.1.3-test 在 1920×1080 安卓模拟器上的实际截图。IP 地址为模拟器地址，不是真实家庭设备地址。

## 0.1.4-test 画质控制 · 2026-10-20

- 构建、Android Lint 通过；8 项档位模型检查、29 项协议检查、10 项格式识别检查通过。
- 原生安卓回归 26 项通过：实际解码尺寸、遥控器入口、360p / 1080p 切换、暂停保留选择、4K 档位与 3840×2160 解码、恢复自动、固定片源提示、新投屏重置、过期会话拒绝及停止清理。
- 测试环境为 Android 11 / API 30 ARM64 模拟器；画面是合成纯色 H.264，2fps，非真实直播节目。4K 解码不代表在索尼真机上验证了 4K / 60fps 或 HDR。
- 真实 Z视介、小红书、抖音是否提供多分辨率投屏地址仍待用户复测。

### 重现原生画质回归

需要 Android Emulator、ADB、Python 3 和系统 FFmpeg（带 libx264）。生成测试素材的工具和测试 runner 均在仓库中，素材不入库。

```sh
python3 tests/make_quality_media.py .test-media
python3 -m http.server 18099 --bind 127.0.0.1 --directory .test-media
```

保持上述媒体服务运行，在另一个终端完成本地签名生成与应用构建，再编译测试程序：

```sh
bash build-local.sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.local.livingroomcast.test/cn.local.livingroomcast.QualityInstrumentation
```

此 runner 使用 Android Emulator 的 10.0.2.2 访问宿主媒体服务；不是可直接用于真实电视的测试脚本。只连接一个测试模拟器，或用 adb -s 指定目标。模拟器需联网；历史 1920×1080 / density 320 配置中已完成遥控器与截图检查。若测试设备不支持给定 4K 格式，该档位应被过滤，runner 将跳过 4K 解码断言并记录结果。

## 0.1.5-test 覆盖层优化 · 2026-10-20

构建与 Android Lint 通过，38 项原生安卓回归检查通过。新增覆盖：正常播放立即隐藏、周期更新不再弹出、方向键唤出、连续操作刷新两秒计时、两秒后收起、暂停保留、恢复播放收起、菜单超过两秒不关闭、新会话不被旧弹窗回调唤出、报错提示持续可见。原有清晰度选择及会话隔离也在同一 runner 中通过。

测试发现并修复旧清晰度弹窗异步关闭回调会再次唤出新视频控制层的问题。测试场景还包含合成 HTTP 404 错误，确保失败状态仍能看见信息。

本次产品逻辑只改 MainActivity 的显示与弹窗生命周期；其他应用代码仅更新版本号（0.1.5-test / versionCode 6）。没有继续排查真实 App 清晰度问题，也没有重跑未改变的协议 / 格式独立检查。原生测试环境及合成媒体条件与前节相同，仍不能代替索尼真机验收。
