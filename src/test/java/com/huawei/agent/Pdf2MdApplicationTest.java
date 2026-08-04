package com.huawei.agent;

import com.huawei.agent.core.parser.MarkdownSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Pdf2MdApplicationTest {
    @Test
    void serializesSectionsAsValidJsonText() {
        MarkdownSection section = MarkdownSection.builder()
                .title("A \"title\"")
                .content("line 1\nline \\ 2")
                .type("paragraph")
                .order(1)
                .build();

        assertEquals("[\n  {\"title\":\"A \\\"title\\\"\",\"content\":\"line 1\\nline \\\\ 2\","
                + "\"type\":\"paragraph\",\"order\":1}\n]", Pdf2MdApplication.toJson(List.of(section)));
    }
}
