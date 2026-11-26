# `:core:textextract`

端侧文本抽取模块：图片 OCR 与未加密 PDF 嵌入文本抽取。输出统一为 `ExtractedText`，每行保留坐标、页索引/图像坐标空间、置信度（仅 OCR）、来源哈希、引擎/模型版本、模型 SHA-256、`DataProvenance` 与 `UNCONFIRMED` 确认状态，供后续 inbox/knowledge 做原文高亮回溯和用户确认。

## 边界

- 离线端侧：不依赖 GMS，不下载模型，不上传图片、PDF 或抽取文本。
- OCR：PP-OCRv6_tiny ONNX + ONNX Runtime Android 1.21.1；不使用 OpenCV。检测后处理、轮廓、最小面积矩形、透视裁剪和缩放在本模块内用纯 Kotlin 实现，算法语义参考 PaddleOCR `deploy/ppocr-android`。
- PDF：PdfBox-Android 2.0.27.0 抽取嵌入文本与显示页左上角 PDF point 坐标；页旋转会折算进四点行框，扫描件 PDF 不在本模块隐式渲染，调用方可把页面位图走 OCR 输入。
- 加密 PDF：首版为控制体积排除 BouncyCastle 传递依赖，加密 PDF 以 `PDF_ENCRYPTION_UNSUPPORTED` / `PDF_ENCRYPTED` 明确失败，不静默降级。
- 原件持久化、`blob_store`、擦除链路和分享入口属于 W1-03/W2-05，不在本卡范围。

## 信任语义

`ExtractedText.confirmationState` 初始一律为 `UNCONFIRMED`，`metadata.requiresUserConfirmation=true`。共享 `VerificationLevel` 当前没有 `UNVERIFIED`/`DERIVED` 值，且本卡被限制不得修改核心共享接口；因此 `DataProvenance.trust` 只使用现有的 `MOCK_TESTED` 表示“合成夹具链路证据”，绝不表示逐行内容已经真实设备或学校验证，也绝不标 `DEVICE_TESTED`。

## Android 使用

```kotlin
val service = AndroidTextExtractors.create(context)
try {
    val imageResult = service.extractImage(
        ImageExtractionRequest(bitmap.toArgbImage(), extractionContext)
    )
    val pdfResult = service.extractPdf(
        PdfExtractionRequest(pdfBytes, extractionContext)
    )
} finally {
    service.close()
}
```

模型 assets 在加载时重新计算 SHA-256，不匹配即 fail-closed。默认 tiny 模型不支持完整日文；复杂拍照、深色模式、200% 字号和真实校园截图仍需后续评测集验证。

## 许可证与体积

第三方来源、固定 revision、哈希、排除项和静态体积测量见 [licenses/THIRD_PARTY.md](licenses/THIRD_PARTY.md)。重点是估算而非 APK 实测：

- arm64 split OCR 估算约 12.16 MiB：ONNX Runtime arm64 compressed 6,465,334 B + tiny 双模型 gzip 5,696,970 B。
- universal 四 ABI OCR 估算约 33.4 MiB + Kotlin SDK，显著超过 arm64 split；这与 R02 的 ABI 策略风险一致。
- PdfBox-Android AAR 3,254,019 B（排除 BouncyCastle 后）；DEX/APK 重打包会改变实际增量。
- 本卡没有把 `:app` 接到新模块，因此当前分支 APK 实际增量为 0；上述数字是集成后的静态估算，PR 中必须明确区分，不得写成实测。

## 测试实现说明

`src/test/resources/com/tom_roush/pdfbox/resources/` 是从 PdfBox-Android AAR assets 提取的资源副本（许可随上游 LICENSE/NOTICE，含 SIL OFL 字体），只用于 JVM local unit test 的 classpath fallback；生产 Android 路径仍由 `PDFBoxResourceLoader` 从 dependency assets 加载。

## 门禁

```bash
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w2-04-textextract quick :core:textextract
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w2-04-textextract android
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w2-04-textextract secrets
```
