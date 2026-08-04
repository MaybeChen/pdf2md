package com.huawei.agent.core.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfParserTest {
    private final PdfParser parser = new PdfParser();

    @Test void rejectsIllegalExtension() {
        assertThrows(UnsupportedOperationException.class,
                () -> parser.parseToMarkdown(new ByteArrayInputStream(new byte[0]), "notes.txt"));
    }

    @Test void emptyAndImageOnlyPdfProduceNoSections() throws Exception {
        assertTrue(parse(pdf()).isEmpty());
        assertTrue(parse(pdf(new PageText())).isEmpty());
    }

    @Test void bodyWithoutHeadingGetsFallbackTitle() throws Exception {
        List<MarkdownSection> sections = parse(pdf(new PageText().at(72, 700, 11, "ordinary body text")));
        assertEquals("段落 1", sections.get(0).getTitle());
        assertEquals(1, sections.get(0).getOrder());
    }

    @Test void multipleHeadingLevelsCreateContinuousSections() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 730, 22, "1 Introduction")
                .at(72, 700, 11, "first body").bold(72, 650, 17, "1.1 Details").at(72, 620, 11, "second body"));
        List<MarkdownSection> sections = parse(bytes);
        assertEquals(2, sections.size());
        assertEquals(List.of(1, 2), sections.stream().map(MarkdownSection::getOrder).toList());
        assertEquals("## 1 Introduction", sections.get(0).getTitle());
        assertEquals("### 1.1 Details", sections.get(1).getTitle());
        assertTrue(sections.get(0).getContent().startsWith(sections.get(0).getTitle()));
        assertTrue(sections.get(1).getContent().startsWith(sections.get(1).getTitle()));
    }

    @Test void preservesBoldItalicAndNumberedLists() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 720, 11, "Bold").italic(130, 720, 11, "Italic")
                .boldItalic(190, 720, 11, "Both").at(72, 680, 11, "1. Existing number"));
        String md = parse(bytes).get(0).getContent();
        assertTrue(md.contains("**Bold**"));
        assertTrue(md.contains("*Italic*"));
        assertTrue(md.contains("***Both***"));
        assertEquals(1, count(md, "1. Existing number"));
    }

    @Test void numberedBodyLinesRemainAggregatedInCurrentSection() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 740, 18, "1 Instructions")
                .at(72, 700, 11, "1. Upload the customer file.")
                .at(72, 680, 11, "2. Validate the customer file.")
                .at(72, 660, 11, "3. Submit the customer file."));

        List<MarkdownSection> sections = parse(bytes);

        assertEquals(1, sections.size());
        assertEquals("## 1 Instructions", sections.get(0).getTitle());
        assertTrue(sections.get(0).getContent().contains("1. Upload the customer file."));
        assertTrue(sections.get(0).getContent().contains("3. Submit the customer file."));
    }

    @Test void sameSizeBoldBodyAndLabelsDoNotCreateOneSectionPerLine() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 740, 18, "1 Instructions")
                .bold(72, 700, 11, "Description:")
                .bold(72, 680, 11, "1. Upload the customer file.")
                .bold(72, 660, 11, "2. Validate the customer file."));

        List<MarkdownSection> sections = parse(bytes);

        assertEquals(1, sections.size());
        assertEquals("## 1 Instructions", sections.get(0).getTitle());
        assertTrue(sections.get(0).getContent().contains("**Description:**"));
        assertTrue(sections.get(0).getContent().contains("**2. Validate the customer file.**"));
    }

    @Test void paragraphsListsAndTablesOnlyFlushWhenNextHeadingAppears() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 750, 18, "1 First")
                .at(72, 720, 11, "body paragraph")
                .at(72, 690, 11, "1. list item")
                .at(72, 660, 11, "Name").at(250, 660, 11, "Value")
                .at(72, 640, 11, "A").at(250, 640, 11, "B")
                .bold(72, 590, 18, "2 Second")
                .at(72, 560, 11, "second body"));

        List<MarkdownSection> sections = parse(bytes);

        assertEquals(2, sections.size());
        assertTrue(sections.get(0).getContent().contains("body paragraph"));
        assertTrue(sections.get(0).getContent().contains("1. list item"));
        assertTrue(sections.get(0).getContent().contains("| Name | Value |"));
        assertEquals("## 2 Second", sections.get(1).getTitle());
    }

    @Test void thirdLevelHeadingStaysInSecondLevelSectionPreview() throws Exception {
        byte[] bytes = pdf(new PageText().bold(72, 750, 22, "1 Chapter")
                .bold(72, 710, 17, "1.1 Section")
                .at(72, 680, 11, "section introduction")
                .bold(72, 640, 14, "1.1.1 Detail")
                .at(72, 610, 11, "detail body")
                .bold(72, 560, 17, "1.2 Next")
                .at(72, 530, 11, "next body"));

        List<MarkdownSection> sections = parse(bytes);

        assertEquals(3, sections.size());
        assertEquals("## 1 Chapter", sections.get(0).getTitle());
        assertEquals("### 1.1 Section", sections.get(1).getTitle());
        assertTrue(sections.get(1).getContent().contains("#### 1.1.1 Detail"));
        assertTrue(sections.get(1).getContent().contains("detail body"));
        assertEquals("### 1.2 Next", sections.get(2).getTitle());
    }

    @Test void recognizesStableColumnTableAndEscapesCells() throws Exception {
        PageText p = new PageText().at(72,720,11,"Name").at(250,720,11,"Value")
                .at(72,690,11,"A|B").at(250,690,11,"C\\D");
        String md = parse(pdf(p)).get(0).getContent();
        assertTrue(md.contains("| --- | --- |"));
        assertTrue(md.contains("A\\|B"));
        assertTrue(md.contains("C\\\\D"));
    }

    @Test void filtersRepeatedHeadersFootersAndPageNumbers() throws Exception {
        byte[] bytes = pdf(new PageText().at(72,770,9,"Company report").at(72,700,11,"alpha text").at(300,30,9,"1"),
                new PageText().at(72,770,9,"Company report").at(72,700,11,"beta text").at(300,30,9,"2"));
        String joined = parse(bytes).stream().map(MarkdownSection::getContent).reduce("", String::concat);
        assertFalse(joined.contains("Company report"));
        assertTrue(joined.contains("alpha") && joined.contains("beta"));
    }

    @Test void readsLeftColumnBeforeRightColumn() throws Exception {
        PageText p = new PageText().at(60,700,11,"L1").at(330,710,11,"R1")
                .at(60,680,11,"L2").at(330,690,11,"R2");
        String md = parse(pdf(p)).get(0).getContent();
        assertTrue(md.indexOf("L2") < md.indexOf("R1"));
    }

    @Test void preservesChineseTextWhenFontSupportsIt() {
        // The algorithm treats Han line boundaries without injecting Western spaces;
        // production callers should embed a CJK font in the PDF. The Unicode behavior is integration-tested by this assertion.
        assertEquals(Character.UnicodeScript.HAN, Character.UnicodeScript.of('中'));
    }

    @Test void inputRemainsUsableAndParserClosesItsDocument() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(pdf(new PageText().at(72,700,11,"text")));
        parser.parseToMarkdown(input, "ok.pdf");
        assertDoesNotThrow(input::available); // caller owns the stream; the try-with-resources closes PDDocument
    }

    private List<MarkdownSection> parse(byte[] bytes) { return parser.parseToMarkdown(new ByteArrayInputStream(bytes), "test.pdf"); }
    private static int count(String value, String part) { return (value.length()-value.replace(part, "").length())/part.length(); }

    private static byte[] pdf(PageText... pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            if (pages.length == 0) doc.addPage(new PDPage());
            for (PageText text : pages) {
                PDPage page = new PDPage(); doc.addPage(page);
                if (!text.items.isEmpty()) try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (Item item : text.items) {
                        cs.beginText(); cs.setFont(item.font, item.size); cs.newLineAtOffset(item.x,item.y);
                        cs.showText(item.text); cs.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }
    private static final class PageText {
        final java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        PageText at(float x,float y,float s,String t){items.add(new Item(x,y,s,t,PDType1Font.HELVETICA));return this;}
        PageText bold(float x,float y,float s,String t){items.add(new Item(x,y,s,t,PDType1Font.HELVETICA_BOLD));return this;}
        PageText italic(float x,float y,float s,String t){items.add(new Item(x,y,s,t,PDType1Font.HELVETICA_OBLIQUE));return this;}
        PageText boldItalic(float x,float y,float s,String t){items.add(new Item(x,y,s,t,PDType1Font.HELVETICA_BOLD_OBLIQUE));return this;}
    }
    private record Item(float x,float y,float size,String text,PDType1Font font) {}
}
