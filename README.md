# pdf2md

基于 PDFBox 文本坐标、面向版面结构的 PDF → Markdown Java 21 解析器。解析器是 Spring `@Component`，Maven 坐标为 `com.huawei.agent:pdf2md`。

## 构建

先确认本机安装了 JDK 21 和 Maven 3.9 或更高版本：

```bash
java -version
mvn -version
```

```bash
mvn clean test
mvn package
```

## 运行

仓库提供了一个命令行入口。将 `example.pdf` 替换为实际 PDF 路径；解析得到的 section JSON 会写到标准输出：

```bash
mvn compile exec:java \
  -Dexec.mainClass=com.huawei.agent.Pdf2MdApplication \
  -Dexec.args="example.pdf"
```

需要把 JSON 保存到文件时可以重定向标准输出：

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.huawei.agent.Pdf2MdApplication \
  -Dexec.args="example.pdf" > output.json
```

命令行参数必须是一个 `.pdf` 文件。参数缺失或多于一个时程序会打印用法并以状态码 2 退出；文件打不开、PDF 损坏或加密文件没有密码时会返回包含文件名的异常。

### 在 IntelliJ IDEA 中运行

可以直接在 IntelliJ IDEA 中构建和运行：

1. 选择 **File → Open**，打开包含 `pom.xml` 的仓库根目录。
2. 等待 IDEA 完成 Maven 导入；如果没有自动导入，在 `pom.xml` 上右键选择 **Add as Maven Project**。
3. 在 **File → Project Structure → Project** 中将 Project SDK 和 Language level 都设为 **JDK 21**。
4. 打开 `src/main/java/com/huawei/agent/Pdf2MdApplication.java`，点击 `main` 方法左侧的绿色运行按钮。
5. 首次运行会因为缺少参数而显示用法。选择 **Run → Edit Configurations**，在该 Application 配置的 **Program arguments** 中填入 PDF 的绝对路径，例如：

   ```text
   /Users/example/Documents/report.pdf
   ```

   如果路径包含空格，请用双引号包裹，例如 `"/Users/example/My Documents/report.pdf"`。
6. 再次运行后，解析出的 JSON 会显示在 IDEA 的 **Run** 控制台中。

也可以打开 IDEA 右侧的 **Maven** 工具窗口，依次运行 `Lifecycle → test` 或 `Lifecycle → package`。单元测试可以在 `src/test/java` 目录或具体测试类上右键选择 **Run Tests**。

## Java 调用示例

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
