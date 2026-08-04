package com.huawei.agent.core.parser;

import java.io.InputStream;
import java.util.List;

public interface FileParser {
    List<MarkdownSection> parseToMarkdown(InputStream inputStream, String fileName);
    String[] getSupportedExtensions();
}
