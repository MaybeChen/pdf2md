package com.huawei.agent;

import com.huawei.agent.core.parser.MarkdownSection;
import com.huawei.agent.core.parser.PdfParser;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Minimal command-line entry point; applications may instead inject PdfParser as a Spring component. */
public final class Pdf2MdApplication {
    private Pdf2MdApplication() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: pdf2md <input.pdf>");
            System.exit(2);
        }

        Path inputPath = Path.of(args[0]);
        List<MarkdownSection> sections;
        try (InputStream input = Files.newInputStream(inputPath)) {
            sections = new PdfParser().parseToMarkdown(input, inputPath.getFileName().toString());
        }
        System.out.println(toJson(sections));
    }

    static String toJson(List<MarkdownSection> sections) {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < sections.size(); i++) {
            MarkdownSection section = sections.get(i);
            if (i > 0) json.append(",\n");
            json.append("  {\"title\":\"").append(escape(section.getTitle()))
                    .append("\",\"content\":\"").append(escape(section.getContent()))
                    .append("\",\"type\":\"").append(escape(section.getType()))
                    .append("\",\"order\":").append(section.getOrder()).append('}');
        }
        return json.append("\n]").toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) escaped.append(String.format("\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return escaped.toString();
    }
}
