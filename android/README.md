# 黑匣子日记 · Android

当前版本 **1.3.2-daily-summary**，versionCode 6。Kotlin + Jetpack Compose，Android 14（API 34）及以上。

- [安装、使用与构建](../README.md)
- [模型与资源准备](../docs/models.md)
- [技术架构](../docs/architecture.md)
- [测试与验证](../docs/testing.md)

在仓库根目录先运行 `python android/tools/prepare-assets.py`，再进入本目录构建：

```powershell
.\gradlew.bat :core:test :app:assembleDebug
```

调试应用 ID 为 `com.blackbox.diary.debug`；故障测试为独立的 `com.blackbox.diary.fault`；负载测试为 `com.blackbox.diary.benchmark`。

`app` 保存 Android 界面、采集、数据库和模型调用，`core` 保存领域规则与 JVM 测试，`native/spark-sme2` 保存 JNI 适配源码和匹配头文件。运行库与本地识别模型由固定版本资源包准备，大语言模型通过设置从本地文件导入。
