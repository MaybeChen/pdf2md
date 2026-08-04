package com.huawei.agent.core.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownSectionTest {
    @Test
    void paragraphUsesFirstMarkdownHeadingAsTitle() {
        MarkdownSection section = MarkdownSection.paragraph("## 1 Title\nbody", 3);
        assertEquals("## 1 Title", section.getTitle());
        assertEquals("paragraph", section.getType());
        assertEquals(3, section.getOrder());
    }

    @Test
    void paragraphWithoutHeadingUsesFallbackTitle() {
        assertEquals("段落 1", MarkdownSection.paragraph("body", 1).getTitle());
    }
}
