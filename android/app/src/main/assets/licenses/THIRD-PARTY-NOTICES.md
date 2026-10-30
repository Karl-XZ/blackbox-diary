# 黑匣子日记 · 第三方组件

本项目没有联网、遥测、广告或账号 SDK。以下依赖的原始许可保留在本目录及各发行包中。

| 组件 | 版本 / 来源 | 许可 |
| --- | --- | --- |
| AndroidX / Jetpack Compose / WorkManager | 依赖版本见 Gradle 锁定文件 | Apache-2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | Kotlin 2.4.0；版本见构建脚本 | Apache-2.0 |
| LiteRT-LM | Google AI Edge 0.18.0 | Apache-2.0 |
| sherpa-onnx | k2-fsa 1.13.5，官方 Android AAR | Apache-2.0 |
| SenseVoice small INT8 | sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17，附词表 | Apache-2.0 |
| Silero VAD | k2-fsa 官方 asr-models 发行资产 | MIT |
| PP-OCRv4 检测与识别模型 | RapidOCR ONNX Runtime 1.4.4 wheel 中的 Paddle 模型 | Apache-2.0 |
| ONNX Runtime | Microsoft 1.21.0，JNI 内部依赖名称隔离 | MIT |
| SQLCipher Android | Zetetic 4.19.1 社区发行 | BSD 风格，见 SQLCipher-LICENSE.txt |
| Spark-X2.5-4B INT4 | litert-community/Spark-X2.5-4B；基础模型 XHToken/Spark-X2.5-4B | Apache-2.0 |
| Spark-X2.5-4B Q4_K_M | XHToken/Spark-X2.5-4B-GGUF，用户本地导入 | Apache-2.0 |
| llama.cpp | b10828 + PR29219；版本与二进制指纹见 native/spark-sme2/provenance.json | MIT |
| KleidiAI | 1.31.0，ARM CPU / SME2 后端 | Apache-2.0 |
| multilingual-e5-small | INT8 ONNX，来源见 memory-model-provenance.json | MIT |
| CAMPPlus zh/en | 3D-Speaker 声纹模型，来源见 memory-model-provenance.json | Apache-2.0 |

Paddle 模型版权：Copyright (c) 2020 PaddlePaddle Authors. All Rights Reserved.
RapidOCR 维护者：RapidAI。使用的模型保持原始权重；应用内的检测框提取和 CTC 解码实现由本项目提供。

ONNX Runtime AAR 的隔离修改只调整原生动态库的 SONAME 和依赖名称，避免与 sherpa-onnx 自带运行时冲突。模型、算子与 Java 公共 API 未修改。来源和完整 SHA-256 见 app/libs/onnxruntime-ocr-isolated-1.21.0.provenance.json。

Spark 模型为社区转换：原始 bf16 权重转换为 LiteRT FlatBuffer，线性层量化为 INT4，嵌入为 INT8；分词器与聊天模板重新封装。没有训练或微调。本项目不属于讯飞或 SparkLLM 团队。模型文件通过用户从本地选择后导入，不由应用联网下载。

2026-10-28，本项目派生 bbd1 包仅移除带标点前缀的 EOS 停止序列，保留 EOS token ID 1 和裸 EOS 字符串，以修正运行时吞掉 JSON 末尾括号的问题。由官方 litert-lm-builder 0.18.0 重打包；分词器、解码器和嵌入权重按 section 的 SHA-256 核对为字节一致，其他 LLM 元数据和 section 配置不变。原包与派生包完整指纹见 android/models/model-repack-provenance.json，重现脚本为 android/tools/repair-spark-stops.py。派生文件仍采用 Apache-2.0。

模型文件名、大小与 SHA-256 见 model-manifest.json 及发行目录中的 Spark 校验信息。

源项目：

- https://github.com/google-ai-edge/LiteRT-LM
- https://github.com/k2-fsa/sherpa-onnx
- https://github.com/FunAudioLLM/SenseVoice
- https://github.com/snakers4/silero-vad
- https://github.com/RapidAI/RapidOCR
- https://github.com/PaddlePaddle/PaddleOCR
- https://github.com/microsoft/onnxruntime
- https://github.com/sqlcipher/sqlcipher-android
- https://huggingface.co/litert-community/Spark-X2.5-4B

完整 Apache-2.0 许可见 Apache-2.0.txt；MIT 组件许可随附。
