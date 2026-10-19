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
