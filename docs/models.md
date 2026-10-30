# 模型与构建资源

## 一次准备构建资源

在仓库根目录运行：

```powershell
python android/tools/prepare-assets.py
```

脚本读取 [`runtime-assets.json`](../android/tools/runtime-assets.json)，从本仓库 v1.3.2 Releases 下载固定资源包，校验压缩包和逐文件 SHA-256。资源包含本地识别模型、AAR 依赖及 ARM64 运行库。资源包不包含 Spark 大语言模型权重。

已有 ZIP 时可离线安装，或只验证当前资源：

```powershell
python android/tools/prepare-assets.py --archive C:/Downloads/blackbox-diary-runtime-assets-v1.3.2.zip
python android/tools/prepare-assets.py --verify-only
```

## Spark-X2.5-4B GGUF

- 来源：[XHToken/Spark-X2.5-4B-GGUF](https://huggingface.co/XHToken/Spark-X2.5-4B-GGUF)。
- 文件：`Spark-X2.5-4B-Q4_K_M.gguf`。
- 大小：`2600224352` 字节。
- SHA-256：`adfcfa19a4ed6a5985da8bf565fe15f8e1a7e131d79bae2d19d48d1c40109428`。
- 手机入口：设置 → 离线模型 → 选择本地文件。

导入逐段复制并校验，只接受与代码内固定指纹相符的文件。GGUF 导入成功且 JNI 可用时，应用优先使用 llama.cpp / SME2 CPU 路径。实际使用的上下文上限为 4096 token。

运行库基于 llama.cpp b10828 + PR29219、KleidiAI 1.31.0，项目适配增加任务取消、输入限制、隐私输出日志清理和温度自适应线程。匹配头文件、JNI 源码、许可与指纹保存在 [`native/spark-sme2/`](../android/native/spark-sme2/)。

## LiteRT-LM 兼容路径

原始转换来源：[litert-community/Spark-X2.5-4B](https://huggingface.co/litert-community/Spark-X2.5-4B)。本项目 `bbd1` 只修复停止序列元数据，保留分词器、嵌入及模型权重 section 的字节内容。

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `Spark-X2.5-4B_int4-bbd1.litertlm` | `2482196400` | `de80fca7a89c7ccac5301d1525b870a671f8ac0541066ef46b9c9d7e7b1aa146` |

准备原始 `Spark-X2.5-4B_int4.litertlm` 后，按 [`repair-spark-stops.py`](../android/tools/repair-spark-stops.py) 和 [`model-builder-requirements.txt`](../android/tools/model-builder-requirements.txt) 重打包；输出校验值必须与上表一致。过程指纹见 [`model-repack-provenance.json`](../android/models/model-repack-provenance.json)。

## APK 内置模型

| 用途 | 组件 / 文件 | 上游 |
| --- | --- | --- |
| 语音识别 | SenseVoice small INT8 | [FunAudioLLM/SenseVoice](https://github.com/FunAudioLLM/SenseVoice)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| 语音分段 | Silero VAD | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| 屏幕 OCR | PP-OCRv4 det / rec、方向分类模型 | [RapidOCR](https://github.com/RapidAI/RapidOCR)、[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) |
| 语义编码 | multilingual-e5-small INT8 与 tokenizer | [intfloat/multilingual-e5-small](https://huggingface.co/intfloat/multilingual-e5-small) |
| 声纹 | CAMPPlus zh/en | [csukuangfj/speaker-embedding-models](https://huggingface.co/csukuangfj/speaker-embedding-models)、[3D-Speaker](https://github.com/modelscope/3D-Speaker) |

APK 中的 [`model-manifest.json`](../android/app/src/main/assets/model-manifest.json) 固定内置模型的大小与哈希。首次使用时复制到应用私有目录并检查；模型权重无须应用联网下载。

## 运行库与许可

资源包中的 `sherpa-onnx-1.13.5.aar` 用于语音与声纹。`onnxruntime-ocr-isolated-1.21.0.aar` 修改原生库 SONAME 与依赖名，避免两个 ONNX Runtime 冲突；来源和修改指纹见 [`provenance`](../android/app/libs/onnxruntime-ocr-isolated-1.21.0.provenance.json)。

完整第三方许可位于 [`assets/licenses/`](../android/app/src/main/assets/licenses/)。E5、声纹模型的版本和转换来源保存在 [`memory-model-provenance.json`](../android/app/src/main/assets/licenses/memory-model-provenance.json)。
