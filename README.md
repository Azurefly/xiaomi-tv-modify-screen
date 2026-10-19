# 小狗便利店投屏

一个用于 Android TV 的轻量 DLNA 视频接收端。打开电视上的应用，在平板视频软件中点 TV / 投屏，选择“小狗便利店投屏”。

最初用于家里的安卓平板向索尼电视投送直播。没有账号、广告、订阅收费或人为播放时长限制。当前版本 **0.1.3-test**，仍处于测试阶段。

![等待投屏界面](docs/images/waiting.png)

## 能做什么

- 通过标准 DLNA / UPnP 发现电视并接收视频地址。
- 用 AndroidX Media3 播放 HTTP(S)、HLS 和 DASH 视频，支持基本播放、暂停、停止和音量控制。
- 识别部分把 HLS 错标成 MP4 的视频地址；解析失败时做一次有限内容检查。
- 显示连接状态与错误代码；连接记录只保存在内存中，不记录完整视频地址或令牌。

应用只接收发送端提供的视频地址，不提供节目、直播源或账号授权服务。

## 兼容情况与限制

| 项目 | 当前情况 |
|---|---|
| 索尼 XR-85X95EK + 安卓平板 | 用户已反馈 Z视介、小红书、抖音可以投屏；具体 App 版本未记录 |
| Android 11 / API 30 模拟器 | 合成 HLS 播放、基本控制及部分错误恢复曾通过测试 |
| 其他电视、手机和视频 App | 待测试，不保证兼容 |
| 两小时连续播放、声画同步、4K / HDR | 尚未完成验收 |

- 最低 Android 6.0 / API 23，需要允许安装 APK。
- 电视和平板应在同一 IPv4 局域网；访客网络、AP 隔离和部分 Mesh 设置会阻止设备发现。
- 需要保持本应用在前台。返回桌面或退出即停止接收，没有开机启动。
- 暂不支持 AirPlay、Miracast、屏幕镜像、乐联私有协议或跨网投屏。
- 不支持 DRM、需要接收端登录或额外私有请求头的播放链路。地址过期通常需要重新投屏。
- 局域网控制没有配对认证，请在可信的家庭网络中使用，不要将接收端口映射到公网。

## 安装与使用

下载 [0.1.3-test 测试安装包](https://github.com/leiz56638111-create/puppy-store-cast/releases/download/v0.1.3-test/PuppyStoreCast-0.1.3-test.apk)，或查看 [发布说明、源码和校验值](https://github.com/leiz56638111-create/puppy-store-cast/releases/tag/v0.1.3-test)。也可按下节自行编译。

1. 把 APK 拷到 U 盘，通过电视文件管理器打开；按电视提示允许对应应用安装未知来源的软件。
2. 安装并打开“小狗便利店投屏”，确认电视已联网。
3. 平板连接同一家庭网络，在支持投屏的视频软件中点 TV / 投屏，选择“小狗便利店投屏”。
4. 播放时用遥控器方向键唤出操作栏，返回键结束本次播放。

搜不到设备时，先确认应用保持打开，再检查是否处于访客网络或启用了设备隔离。收到地址但无法播放时，查看“连接详情”中的错误代码。

## 从源码构建

需要 JDK 17 或 21、Android SDK Platform 36、Build Tools 35.0.0，以及首次下载依赖所需的网络。工程带 Gradle 8.13 Wrapper，不需要全局安装 Gradle。

macOS / Linux：设置 `JAVA_HOME` 和 `ANDROID_HOME` 后执行：

```sh
bash build-local.sh
```

Windows PowerShell：设置上述环境变量后，在工程根目录执行：

```powershell
New-Item -ItemType Directory -Force signing | Out-Null
if (!(Test-Path signing/test.keystore)) {
  & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore signing/test.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=LivingRoomCast Test,O=Local Development,C=CN"
}
.\gradlew.bat --no-daemon assembleDebug lintDebug
```

Windows 路径仅提供操作说明，尚未在 Windows 真机验证。也可使用 Android Studio 打开工程、选择 SDK，并按上述方式生成本地测试密钥。

输出为 `app/build/outputs/apk/debug/app-debug.apk`。

### 签名与升级

构建脚本会为缺少密钥的源码副本生成本地调试密钥。示例密码 `android` 是调试配置，不是生产环境凭据。

公开的 0.1.3-test 安装包沿用此前家庭测试版的测试签名，与此前交付的是同一文件；可以覆盖同包名、同签名的已有安装。仓库不包含该签名私钥。不同人自行编译的 APK 签名不同，不能直接覆盖彼此的安装；后续签名安排以各版本发布说明为准。

## 测试与参与

```sh
python3 tests/test_protocol.py
python3 tests/test_format_probe.py
```

需要设置 `JAVA_HOME`，并允许 Python / Java 监听本机回环地址。测试说明、历史验收边界见 [TESTING.md](TESTING.md)。

欢迎提交兼容性反馈和改进建议，见 [CONTRIBUTING.md](CONTRIBUTING.md)。UI 打磨和分辨率相关设置属于后续方向，目前尚未实现。

## 许可证

本项目原创代码和文档采用 **GNU GPL v3.0 only**，标识符 `GPL-3.0-only`，完整条款见 [LICENSE](LICENSE)。允许使用、修改和分发，包括商业使用；分发本程序或修改版时，需要依照该许可证提供对应源码、保留通知并标明修改。程序按原样提供，不附带担保。

第三方组件、Gradle Wrapper 和第三方许可证文本保留各自的版权与许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
