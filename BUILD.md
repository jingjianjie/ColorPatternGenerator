# 编译指南 (Build Guide)

本文档说明如何在 Orange Pi 5 Plus / Armbian 环境下编译 `ColorPatternGenerator`。

## 1. 环境依赖

当前板卡环境已配置：

- JDK: OpenJDK 17
- Gradle: `/home/gradle-8.2`
- Android SDK: `/home/android-sdk`
- 项目目录: `/home/ColorPatternGenerator`

环境变量由 `/etc/profile.d/android-dev.sh` 提供：

```bash
source /etc/profile.d/android-dev.sh
```

说明：Android SDK 中部分构建工具为 x86_64 程序，当前系统已通过 `qemu-user-static`、`binfmt-support` 和 amd64 运行库做兼容运行。

## 2. 编译命令

```bash
source /etc/profile.d/android-dev.sh
cd /home/ColorPatternGenerator
gradle --no-daemon --stacktrace assembleDebug
```

## 3. 输出文件

Debug APK：

```text
/home/ColorPatternGenerator/app/build/outputs/apk/debug/colorpatterngenerator_pg.apk
```

随编译自动拷贝的文档：

```text
/home/ColorPatternGenerator/app/build/outputs/apk/debug/manual/
```

当前会拷贝非空的 `PROTOCOL.md`、`BUILD.md`、`DEV_DIARY.md`，以及项目根目录下匹配 `*manual*` / `*protocol*` 的文档类文件。

## 4. 局域网访问

Windows 共享：

```text
\\192.168.31.7\opi-dev
```

备用地址：

```text
\\orangepi5.local\opi-dev
```

登录凭据请向共享目录管理员获取，不要将密码保存在仓库中。

APK 可在共享目录中访问：

```text
\\192.168.31.7\opi-dev\ColorPatternGenerator\app\build\outputs\apk\debug\
```

## 5. 常见问题

- 首次编译会下载依赖，耗时较长；后续增量编译会快很多。
- 如果 AAPT2 报 `Exec format error`，检查 `qemu-user-static`、`binfmt-support` 和 amd64 运行库是否仍可用。
- 如果依赖下载失败，检查 Gradle 代理配置：`/home/orangepi5/.gradle/gradle.properties`。

## 6. GitHub 应用更新

启动后的等待界面提供 **Check for updates** 按钮，显示当前安装版本并检查
[GitHub Releases](https://github.com/jingjianjie/ColorPatternGenerator/releases/latest)。
检测到新版本后，依次点击 **Download update** 和 **Install update**。
首次安装时，Android 会要求允许本应用“安装未知应用”；授权并返回后，由系统安装器要求确认更新。
检查失败或取消授权后可以重试。更新面板在显示图案、姿态校准及推流期间隐藏；触摸锁定时不可点击。

本功能读取已发布的稳定 Release，不会把源码提交当作 APK 更新。尚无 Release 时会显示提示。
首次使用此功能，需要先手动安装包含更新按钮的 APK（版本 1.1，versionCode 2）。

发布后续更新：

1. 在 `app/build.gradle` 中同时提高 `versionCode` 和 `versionName`。
   `versionName` 使用数字点分格式，例如 `1.2` 或 `1.2.1`。
2. 使用与已安装应用相同的签名密钥编译，并运行测试：

   ```bash
   source /etc/profile.d/android-dev.sh
   gradle --no-daemon testDebugUnitTest assembleDebug
   ```

3. 在 GitHub 创建已发布的稳定 Release，标签使用 `v` 加 `versionName`，例如 `v1.2`。
   不要标记为 draft 或 prerelease，并将此版本设为最新 Release。
4. 将 `app/build/outputs/apk/debug/colorpatterngenerator_pg.apk` 作为附件上传，保留文件名
   **colorpatterngenerator_pg.apk**。仅创建标签或推送源码不会提供安装包。

当前 APK 使用此编译机的 debug 签名。持续更新必须保留该签名密钥；换机器或使用 GitHub Actions
重新生成 debug 密钥会导致无法覆盖安装。签名密钥不得提交到 Git 仓库。
应用在启动安装前检查包名、签名、Android 最低版本和递增的 `versionCode`；下载还会检查文件大小，
以及 GitHub 提供的 SHA-256（如有）。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)、
[Android 版本号](https://developer.android.com/studio/publish/versioning)、
[Android 应用签名](https://developer.android.com/studio/publish/app-signing)。
