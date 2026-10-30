# 测试与验证

## 核心规则测试

先按 [README](../README.md#从源码构建) 准备环境与资源。在 `android/` 目录运行：

```powershell
.\gradlew.bat :core:test
```

测试代码位于 [`core/src/test/`](../android/core/src/test/)。覆盖领域状态、Unicode 范围、引用、覆盖率、录音块规则、向量与助手引用、每日总结和版本选择。Gradle 报告输出到 `core/build/reports/tests/test/index.html`。

## 独立真机测试安装

设备自动化会写入合成记录、配置测试 PIN，并验证删除操作。使用专用模拟器或独立 `.fault` 安装；个人使用包为 `.debug`，两者数据隔离。测试文件中的固定 PIN 仅供合成测试使用。

```powershell
.\gradlew.bat :app:assembleFault :app:assembleFaultAndroidTest -PfaultTests=true
adb devices
adb -s <测试设备序列号> install -r app/build/outputs/apk/fault/app-fault.apk
adb -s <测试设备序列号> install -r app/build/outputs/apk/androidTest/fault/app-fault-androidTest.apk
```

每日总结模型测试需要先在 `.fault` 应用中导入 [指定 GGUF 模型](models.md)。

```powershell
adb -s <测试设备序列号> shell am instrument -w -e class com.blackbox.diary.DailySummaryDeviceTest com.blackbox.diary.fault.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用 12 条合成手记验证成篇总结、首页入口、来源不变、人工编辑保护、候选、导出与删除联动。单独运行全套设备测试时，按对应类的前置条件准备权限、模型与空白数据；部分测试会终止自己的进程。

## 故障恢复与搜索负载

故障测试在独立 `.fault` 包中逐个命中切点，进程中断后检查恢复：

```powershell
.\tools\test-faults.ps1 -Serial <测试设备序列号> -Repeats 20
```

搜索负载使用独立 `.benchmark` 包，启用 R8 优化、开发签名：

```powershell
.\gradlew.bat :app:assembleBenchmark
adb -s <测试设备序列号> install -r app/build/outputs/apk/benchmark/app-benchmark.apk
.\tools\test-search.ps1 -Serial <测试设备序列号>
```

报告按硬件、版本和运行条件分别记录；模拟器性能用于回归比较。

## 现有短程记录

2026-10-30 仓库发布时，重新执行 `:core:test`，**45 项通过，失败、错误与跳过均为 0**。另将仓库源码导出到全新目录，用固定资源包还原运行依赖，执行 `:core:test :app:assembleDebug`，43 个 Gradle 任务实际执行，构建成功。

发布检查还包含 6 张原图尺寸、50 处本地文档链接、30 个资源文件哈希、空目录解包、文件损坏后的拒绝与修复、压缩包校验失败时的拒绝。

| 项目 | 条件与记录 |
| --- | --- |
| 设备 | vivo V2509A / Android 16 |
| 版本 | 1.3.2-daily-summary，versionCode 6 |
| 核心回归 | 2026-10-29 记录为 43 项通过 |
| 每日总结设备回归 | 2026-10-29 记录为 3 项通过，测试输入为合成记录 |
| 原安装元数据检查 | 88 条原始 ASR 计数保持不变，日记总结发布，声音采集服务运行 |
| 模型 | 手机内 Spark-X2.5-4B Q4_K_M，SME2 CPU |

个人日记正文、设备标识、原安装数据库及日志保留在用户本地，仓库提供测试代码、合成输入和界面截图。

## 后续验收

| 项目 | 记录方法 |
| --- | --- |
| 8 小时连续采集 | 分别记录声音和屏幕的意图开启、实际采集、主动排除、未知缺口与识别状态 |
| 24 小时稳定性 | 记录进程恢复、重启、锁屏、权限变更、未完成任务续跑与日记发布 |
| 功耗与温度 | 固定设备、亮度、网络和任务条件，记录起止电量、温度、模型调用与前台服务时长 |
| 语音质量 | 按普通话、混合语言、口音、多人和噪声分组，人工核对原文、纠错和归属 |
| 总结事实质量 | 逐句核对支持来源、否定、不确定性、说话人、时间；分别统计遗漏与新增事实 |
| 删除恢复 | 检查跨日依赖、历史版本、中间摘要、向量、对话和待处理任务在删除及重启后的状态 |
