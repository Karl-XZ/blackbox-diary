# 技术架构

## 运行与数据边界

原生应用使用 Kotlin + Jetpack Compose。`app` 模块组织界面、采集、加密存储、模型和后台任务；`core` 模块提供可在 JVM 上独立测试的规则。浏览器原型位于根目录 `dist/`。

手机应用的 Manifest 移除了 `INTERNET` 和 `ACCESS_NETWORK_STATE`。ASR、OCR、向量编码、语言模型和声纹匹配均在设备内运行。构建依赖与模型由开发者在电脑或系统文件管理器中准备。

## 从采集到日记

1. 声音和屏幕采集独立授权、开启和暂停。声音使用麦克风前台服务，屏幕使用 MediaProjection。
2. 音频以 16 kHz 单声道 PCM16 采集，按 60 秒形成加密片段，每秒独立进行 AES-GCM 认证。原始缓存最长保留 24 小时。
3. Silero VAD 提取语音段，SenseVoice 识别语言和文本，根据用户所选语言集合保留相应片段。屏幕图像仅在内存中处理，OCR 结果形成文字来源。
4. 来源事件保留时间、类型、质量、版本和文本摘要。原始 ASR 独立保存；`AudioInterpreter` 生成单独的含义解释，关联邻近来源及分析依据。
5. `DailyComposer` 按时间组织可用材料，将较多材料分批压缩为主题要点，缓存中间摘要，再请求 Spark 输出每日正文。
6. 程序检查引用 ID、来源生命周期、版本、否定、归属和不确定性。模型还对正文进行来源支持检查。通过检查后提交日记版本。
7. 自动整理通过版本比较提交；存在人工修改时产生候选版本，当前人工版本保留。

本人手记、环境声音和屏幕内容具有不同的归属语义。采集时间与事件发生时间分开处理，未知说话人的内容保持环境线索的归属。

核心入口：[`Pipeline.kt`](../android/app/src/main/java/com/blackbox/diary/processing/Pipeline.kt)、[`AudioInterpreter.kt`](../android/app/src/main/java/com/blackbox/diary/processing/AudioInterpreter.kt)、[`DailyComposer.kt`](../android/app/src/main/java/com/blackbox/diary/processing/DailyComposer.kt)。

## 本地记忆与助手

- 索引对象：当前已发布、生命周期有效的日记段落。
- 分块：最多 360 个 Unicode 码点，相邻块重叠 48 个码点；根据分词后长度继续缩短，控制在 256 token 内。
- 编码：multilingual-e5-small INT8，384 维向量，归一化后保存在 SQLCipher 的 `memory_vector` 表。
- 检索：遍历有效向量，以 `0.9 × 余弦相似度 + 0.1 × 关键词匹配比例` 排序，默认保留 6 项。
- 回答：Spark 从候选段落中选择相关原文；程序要求回答文字为引用日记的连续原文，并保留句子中的否定与归属。
- 更新：向量绑定段落、版本和全文哈希。编辑、删除或版本变化后，只使用与当前日记一致的记忆。
- 对话：最近对话用于补充短问题的检索上下文；答案的事实来源仍为当前日记，历史回答随 epoch 和来源存活情况重新检查。

实现入口：[`MemoryIndex.kt`](../android/app/src/main/java/com/blackbox/diary/processing/MemoryIndex.kt)、[`DiaryAssistant.kt`](../android/app/src/main/java/com/blackbox/diary/processing/DiaryAssistant.kt)。

## 模型调度

大型模型操作共用互斥锁。已导入 GGUF 且 JNI 可用时优先使用 llama.cpp / SME2；另保留 LiteRT-LM 路径。总上下文上限为 4096 token，输入与输出分别受限。

任务检查总内存、可用内存、电源、温度及用途额度。SME2 正常使用 4 个 CPU 线程，温升时改为 2 个，严重热状态暂停。生成可中止，模型空闲约 60 秒释放。WorkManager 保存调度，数据库保存任务进度、重试状态和来源指纹。

每天 02:00 为默认计划时间，实际执行受 Android 后台调度约束。启动和恢复时检查遗漏日期；新来源到达后可以触发补整理。

## 加密与删除

SQLCipher 保存正文、来源、历史版本、中间摘要、记忆向量、对话、偏好和处理任务。Android Keystore 管理密钥，应用默认启用 `FLAG_SECURE`，系统备份关闭。

删除日期范围时，`DeletionCoordinator` 提升 epoch，使在途结果失效；停止相关采集与处理，隐藏依赖闭包，清理来源及派生内容。随后把存活数据复制到新 SQLCipher 数据库，重建有效索引、切换存储清单并销毁旧 Keystore alias，确认后写入完成状态。

依赖覆盖日记段落、历史版本、含义解释、中间摘要、记忆索引和任务，避免旧任务或跨日引用将已删内容重新写回。恢复历史版本也要检查来源存活与当前版本。

本地导出使用系统文件选择器。用户选择的 Markdown / TXT 属于独立明文文件，应由用户在导出位置管理。

实现入口：[`DiaryStore.kt`](../android/app/src/main/java/com/blackbox/diary/data/DiaryStore.kt)、[`DeletionCoordinator.kt`](../android/app/src/main/java/com/blackbox/diary/privacy/DeletionCoordinator.kt)、[`CryptoVault.kt`](../android/app/src/main/java/com/blackbox/diary/security/CryptoVault.kt)。

## 覆盖率

声音与屏幕分别计算。分母是用户意图开启的时间，扣除明确排除区间；分子是该范围内的实际采集时间。区间先合并，避免重叠计数。有未知缺口时不输出确定覆盖比例。识别成功率使用成功处理数 / 待处理总数，分母为零时不显示百分比。

“没有语音”“语言被排除”“识别后无文字”和“没有采集到”保留不同状态。算法见 [`Coverage.kt`](../android/core/src/main/kotlin/com/blackbox/diary/core/Coverage.kt)。
