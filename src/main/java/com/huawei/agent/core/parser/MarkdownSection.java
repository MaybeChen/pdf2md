package com.huawei.agent.core.parser;

import java.util.Objects;

public final class MarkdownSection {
    private final String title;
    private final String content;
    private final String type;
    private final int order;

    private MarkdownSection(Builder builder) {
        this.title = builder.title;
        this.content = builder.content;
        this.type = builder.type;
        this.order = builder.order;
    }

    public static Builder builder() { return new Builder(); }

    /** Creates a paragraph section using the same title convention as WordParser. */
    public static MarkdownSection paragraph(String content, int order) {
        String normalized = Objects.requireNonNull(content, "content").strip();
        String firstLine = normalized.lines().findFirst().orElse("").strip();
        String title = firstLine.startsWith("#") ? firstLine : "段落 " + order;
        return builder().title(title).content(normalized).type("paragraph").order(order).build();
    }

    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getType() { return type; }
    public int getOrder() { return order; }

    public static final class Builder {
        private String title;
        private String content;
        private String type;
        private int order;
        public Builder title(String title) { this.title = title; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder order(int order) { this.order = order; return this; }
        public MarkdownSection build() {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(type, "type");
            return new MarkdownSection(this);
        }
    }
}
