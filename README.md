# pdf2md

基于 PDFBox 文本坐标、面向版面结构的 PDF → Markdown Java 17 解析器。解析器是 Spring `@Component`，Maven 坐标为 `com.huawei.agent:pdf2md`。

## 构建

```bash
mvn clean test
mvn package
```

## 调用示例

```java
PdfParser parser = new PdfParser();
try (InputStream in = Files.newInputStream(Path.of("report.pdf"))) {
    List<MarkdownSection> sections = parser.parseToMarkdown(in, "report.pdf");
}
```

Spring 应用也可以扫描 `com.huawei.agent` 包并注入 `FileParser`。输入流由调用者所有；解析器会确定性关闭内部 `PDDocument`。当前只接受扩展名（大小写不敏感）为 `.pdf` 的文件。

输出序列化为 JSON 后形如：

```json
[
  {
    "title": "1 概述",
    "content": "## **1 概述**\n\n这里是正文。",
    "type": "paragraph",
    "order": 1
  }
]
```

## 解析规则与能力边界

解析器不使用 `PDFTextStripper.getText()` 的返回文本作为结果，而是采集每个文本片段的页码、X/Y 坐标、字体、字号及粗斜体。它先按基线聚类成行，再恢复页内阅读顺序；检测到双栏时整栏排序，避免左右交叉。相近的连续行组成段落，英文行末连字符会被修复，中文行之间不会人为插入空格。

标题由正文主字号、字号差、粗体、行宽和中英文章节编号共同推断为 1–5 级，并输出 `##`–`######`。重复页眉/页脚及纯页码会被过滤。编号、项目符号和粗斜体样式会尽量保留。

表格识别依赖文本坐标中连续多行的稳定列起点；首行作为表头。它适合规则、文本型表格，但合并单元格、嵌套表格、旋转文字、不规则列和仅由绘图线组成的复杂表格可能降级成普通段落，以避免生成误导性的 Markdown 表格。

PDF 本身通常不保存语义结构，因此标题、栏和段落均属于启发式推断；特殊排版可能需要业务侧后处理。空文件、只有图片或没有有效文本的 PDF 返回空列表。核心模块不绑定任何 OCR 厂商；扫描件可由应用通过独立的 `OcrProvider` 扩展点接入 OCR，再自行进行结构化处理。加密且没有有效密码的 PDF 会返回包含文件名的明确异常。
