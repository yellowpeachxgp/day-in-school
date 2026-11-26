# core:textextract 第三方来源与许可证

本模块只做端侧离线抽取，不引入 GMS、不引入 OpenCV、不上传图片或文本。

| 组件 | 固定版本 / 来源 | 许可证 | 本模块使用方式 |
|---|---|---|---|
| PaddleOCR PP-OCRv6_tiny detection ONNX | HuggingFace `PaddlePaddle/PP-OCRv6_tiny_det_onnx` revision `2ba1506c0380b8f0b03dd142459aac66d4421f6c` | Apache-2.0（模型卡声明；PaddleOCR 上游同为 Apache-2.0） | bundled asset `ppocrv6_tiny_det.onnx`，SHA-256 `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` |
| PaddleOCR PP-OCRv6_tiny recognition ONNX | HuggingFace `PaddlePaddle/PP-OCRv6_tiny_rec_onnx` revision `2612ab37152ae0a677521bae4e1e3d4fb4cf7c30` | Apache-2.0（模型卡声明；PaddleOCR 上游同为 Apache-2.0） | bundled asset `ppocrv6_tiny_rec.onnx`，SHA-256 `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6` |
| ONNX Runtime Android | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.21.1` | MIT | Android 生产推理运行时；R02 静态测量确认 arm64/x86_64 LOAD align `0x4000` |
| ONNX Runtime JVM | Maven `com.microsoft.onnxruntime:onnxruntime:1.21.1` | MIT | 仅 `testImplementation`，让 JVM 单测在开发机执行同一模型；不进入 APK |
| PdfBox-Android | Maven `com.tom-roush:pdfbox-android:2.0.27.0` | 主代码 Apache-2.0；捆绑 LiberationSans 字体 SIL OFL 1.1，另含 Adobe Glyph List、Unicode Bidi Mirroring、TwelveMonkeys 等上游通知 | 未加密 PDF 文本与坐标抽取；首版排除 BouncyCastle 加密/签名传递依赖 |

许可证全文：Apache-2.0 见 [Apache-2.0.txt](Apache-2.0.txt)，MIT 见 [MIT.txt](MIT.txt)。PDFBox 上游完整 LICENSE（含 Liberation Fonts SIL OFL 1.1 与其他捆绑组件条款）见 [PdfBox-LICENSE-and-bundled-licenses.txt](PdfBox-LICENSE-and-bundled-licenses.txt)，NOTICE 见 [PdfBox-NOTICE.txt](PdfBox-NOTICE.txt)。

## 明确排除

- 不引入 `com.quickbirdstudios:opencv:4.5.3`。R02 测量其 64 位 ELF LOAD 对齐仍不满足 Android 15+ 16 KB 页要求，且 universal APK 原生库体积过大。
- 不引入 Google ML Kit / Play Services OCR。
- 不引入 BouncyCastle。`pdfbox-android` POM 的 `org.bouncycastle` 传递依赖在本模块 Gradle 依赖中排除；加密 PDF 返回明确错误。未加密 PDF 的文本抽取由合成 PDF 夹具测试覆盖。

## 静态体积证据（非完整 APK 实测）

R02 对公开构件的直接测量：ONNX Runtime Android 1.21.1 arm64-v8a native compressed 6,465,334 B；PP-OCRv6_tiny 两个 ONNX gzip-9 合计约 5,696,970 B（本模块重新核验 det 1,589,574 B + rec 4,107,396 B）。arm64 split 的理论 OCR 增量约 12.16 MiB；universal 四 ABI 约 33.4 MiB + Kotlin SDK。PdfBox-Android AAR 文件 3,254,019 B；`classes.jar` entry compressed 1,609,777 B。AAR/JAR 到 DEX/APK 的转换会改变实际体积，因此 PDF 部分只能作为静态估算，不能写成 APK 实测。

## 测试资源副本

`src/test/resources/com/tom_roush/pdfbox/resources/` 是从 PdfBox-Android AAR assets 提取的资源副本（许可随上游 LICENSE/NOTICE，含 SIL OFL 字体），只用于 JVM local unit test 的 classpath fallback；不进入生产 AAR/APK。
