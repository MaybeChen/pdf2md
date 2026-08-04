package com.huawei.agent.core.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class PdfParser implements FileParser {
    private static final Pattern PAGE_NUMBER = Pattern.compile("^(?:[-–—]?\\s*)?(?:第\\s*)?\\d+(?:\\s*页)?(?:\\s*[-–—]?)$");
    private static final Pattern NUMBERED = Pattern.compile("^(?:\\d+(?:\\.\\d+)*[、.)．]?|[一二三四五六七八九十]+、|[（(][一二三四五六七八九十0-9]+[）)])\\s*.+");
    private static final Pattern BULLET = Pattern.compile("^[•●▪◦‣·*+-]\\s*.+");
    private static final Pattern HEADING_NUMBER = Pattern.compile("^(?:\\d+(?:\\.\\d+){0,4}|[一二三四五六七八九十]+、|[（(][一二三四五六七八九十0-9]+[）)])(?:\\s+|(?=[^0-9.]))");

    @Override
    public String[] getSupportedExtensions() { return new String[]{".pdf"}; }

    @Override
    public List<MarkdownSection> parseToMarkdown(InputStream inputStream, String fileName) {
        Objects.requireNonNull(inputStream, "inputStream");
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new UnsupportedOperationException("Unsupported file extension: " + fileName + "; expected .pdf");
        }
        try (PDDocument document = PDDocument.load(inputStream)) {
            LayoutStripper stripper = new LayoutStripper(document);
            stripper.getText(document); // drives processTextPosition; its returned plain text is deliberately ignored
            return toSections(stripper.lines());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new IllegalArgumentException("Cannot open encrypted PDF '" + fileName + "': a valid password is required", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse PDF '" + fileName + "'", e);
        }
    }

    private List<MarkdownSection> toSections(List<Line> source) {
        List<Line> lines = filterMargins(source);
        if (lines.stream().noneMatch(l -> !l.text().isBlank())) return List.of();
        float bodySize = bodyFontSize(lines);
        List<Block> blocks = formBlocks(lines, bodySize);
        List<MarkdownSection> result = new ArrayList<>();
        String title = null;
        StringBuilder content = new StringBuilder();
        for (Block block : blocks) {
            if (block.headingLevel > 0) {
                if (content.length() > 0) addSection(result, title, content);
                // Keep the same contract as WordParser: title contains the complete Markdown
                // heading, while body and heading are both retained in content. Inline font
                // emphasis is redundant (and noisy) inside an inferred heading.
                title = "#".repeat(block.headingLevel + 1) + " " + block.plainText;
                content = new StringBuilder(title).append("\n\n");
            } else {
                content.append(block.markdown).append("\n\n");
            }
        }
        if (content.length() > 0) addSection(result, title, content);
        return result;
    }

    private void addSection(List<MarkdownSection> out, String title, StringBuilder text) {
        int order = out.size() + 1;
        out.add(MarkdownSection.builder().title(title == null ? "段落 " + order : title)
                .content(text.toString().strip()).type("paragraph").order(order).build());
    }

    private List<Line> filterMargins(List<Line> lines) {
        Map<String, Integer> pages = new HashMap<>();
        for (Line l : lines) if (l.margin()) pages.merge(normalize(l.text()), 1, Integer::sum);
        int pageCount = lines.stream().mapToInt(l -> l.page).max().orElse(0);
        return lines.stream().filter(l -> {
            String s = l.text().strip();
            if (s.isEmpty() || PAGE_NUMBER.matcher(s).matches()) return false;
            return !(l.margin() && pageCount > 1 && pages.getOrDefault(normalize(s), 0) >= Math.max(2, (pageCount + 1) / 2));
        }).toList();
    }

    private String normalize(String value) { return value.replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip(); }

    private float bodyFontSize(List<Line> lines) {
        Map<Integer, Integer> weights = new HashMap<>();
        for (Line line : lines) weights.merge(Math.round(line.size * 2), Math.max(1, line.text().length()), Integer::sum);
        return weights.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey() / 2f).orElse(10f);
    }

    private List<Block> formBlocks(List<Line> lines, float body) {
        List<Block> result = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            int tableEnd = tableEnd(lines, i);
            if (tableEnd - i >= 2) {
                result.add(new Block(table(lines.subList(i, tableEnd)), "", 0));
                i = tableEnd; continue;
            }
            Line first = lines.get(i);
            int level = headingLevel(first, body);
            List<Line> paragraph = new ArrayList<>(); paragraph.add(first); i++;
            if (level == 0 && !isList(first.text())) {
                while (i < lines.size() && headingLevel(lines.get(i), body) == 0 && !isList(lines.get(i).text())
                        && lines.get(i).page == paragraph.get(paragraph.size()-1).page
                        && lines.get(i).y - paragraph.get(paragraph.size()-1).y < Math.max(body * 2.2f, 24f)) {
                    paragraph.add(lines.get(i++));
                }
            }
            String markdown = joinParagraph(paragraph);
            result.add(new Block(markdown, first.text().strip(), level));
        }
        return result;
    }

    private int headingLevel(Line line, float body) {
        String text = line.text().strip();
        if (text.length() > 100) return 0;
        float delta = line.size - body;
        boolean numbered = HEADING_NUMBER.matcher(text).find();
        boolean typographicHeading = delta >= 1f
                || (line.boldRatio() > .6 && line.width < line.pageWidth * .8f);

        // A number at the beginning is not sufficient evidence: numbered list items such as
        // "1. Upload a file" are body paragraphs. The previous implementation treated every
        // such line as a heading and consequently ended the current section on every line.
        // Numbering only determines the level after font size/bold/line-width has established
        // that the line is actually a heading.
        if (!typographicHeading) return 0;
        if (delta >= 7) return 1;
        if (delta >= 4) return 2;
        if (delta >= 2) return 3;
        if (numbered) {
            String prefix = text.split("\\s+", 2)[0];
            if (prefix.matches("\\d+(?:\\.\\d+)+.*")) return Math.min(5, 1 + prefix.replaceAll("[^.]", "").length());
            return 1;
        }
        return 4;
    }

    private boolean isList(String text) { return NUMBERED.matcher(text.strip()).matches() || BULLET.matcher(text.strip()).matches(); }

    private String joinParagraph(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String part = lines.get(i).markdown();
            if (i > 0) {
                boolean hyphen = out.length() > 0 && out.charAt(out.length()-1) == '-' && startsLatin(part);
                if (hyphen) out.setLength(out.length()-1);
                else if (!endsCjk(out) && !startsCjk(part)) out.append(' ');
            }
            out.append(part.strip());
        }
        return out.toString();
    }

    private boolean startsLatin(String s) { return !s.isEmpty() && Character.UnicodeScript.of(s.charAt(0)) == Character.UnicodeScript.LATIN; }
    private boolean startsCjk(String s) { return !s.isEmpty() && isCjk(s.charAt(0)); }
    private boolean endsCjk(StringBuilder s) { return s.length() > 0 && isCjk(s.charAt(s.length()-1)); }
    private boolean isCjk(char c) { return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN; }

    private int tableEnd(List<Line> lines, int start) {
        if (lines.get(start).cells().size() < 2) return start;
        List<Float> anchors = lines.get(start).cells();
        int end = start + 1;
        while (end < lines.size() && lines.get(end).page == lines.get(start).page) {
            List<Float> cells = lines.get(end).cells();
            if (cells.size() != anchors.size()) break;
            boolean stable = true;
            for (int c=0;c<cells.size();c++) if (Math.abs(cells.get(c)-anchors.get(c)) > 18) stable=false;
            if (!stable) break;
            end++;
        }
        return end;
    }

    private String table(List<Line> rows) {
        List<String> out = new ArrayList<>();
        for (Line row : rows) out.add("| " + String.join(" | ", row.cellMarkdown()) + " |");
        out.add(1, "| " + String.join(" | ", java.util.Collections.nCopies(rows.get(0).cells().size(), "---")) + " |");
        return String.join("\n", out);
    }

    private record Block(String markdown, String plainText, int headingLevel) {}

    private static final class LayoutStripper extends PDFTextStripper {
        private final PDDocument document;
        private final List<Glyph> glyphs = new ArrayList<>();
        private int page;
        private float pageWidth, pageHeight;
        LayoutStripper(PDDocument document) throws IOException { this.document = document; setSortByPosition(false); }
        @Override protected void startPage(PDPage pdPage) throws IOException {
            super.startPage(pdPage); page++;
            PDRectangle box = pdPage.getCropBox(); pageWidth = box.getWidth(); pageHeight = box.getHeight();
        }
        @Override protected void processTextPosition(TextPosition p) {
            String text = p.getUnicode();
            if (text != null && !text.isEmpty()) {
                String font = p.getFont() == null ? "" : p.getFont().getName().toLowerCase(Locale.ROOT);
                boolean bold = font.contains("bold") || font.contains("black") || font.contains("demi");
                boolean italic = font.contains("italic") || font.contains("oblique");
                glyphs.add(new Glyph(text, page, p.getXDirAdj(), p.getYDirAdj(), p.getWidthDirAdj(), p.getHeightDir(),
                        p.getFontSizeInPt(), bold, italic, pageWidth, pageHeight));
            }
        }
        List<Line> lines() {
            Map<Integer,List<Glyph>> byPage = new LinkedHashMap<>();
            glyphs.forEach(g -> byPage.computeIfAbsent(g.page, k -> new ArrayList<>()).add(g));
            List<Line> all = new ArrayList<>();
            byPage.forEach((p, gs) -> all.addAll(pageLines(gs)));
            return all;
        }
        private List<Line> pageLines(List<Glyph> gs) {
            gs.sort(Comparator.comparingDouble((Glyph g)->g.y).thenComparingDouble(g->g.x));
            List<List<Glyph>> groups = new ArrayList<>();
            for (Glyph g : gs) {
                List<Glyph> best = groups.stream().filter(x -> Math.abs(avgY(x)-g.y) <= Math.max(2.5, g.size*.28)).findFirst().orElse(null);
                if (best == null) { best = new ArrayList<>(); groups.add(best); }
                best.add(g);
            }
            List<Line> lines = groups.stream().map(Line::new).toList();
            float width = gs.isEmpty() ? 0 : gs.get(0).pageWidth;
            long left = lines.stream().filter(l -> l.maxX() < width*.58f).count();
            long right = lines.stream().filter(l -> l.x > width*.42f).count();
            boolean columns = left >= 2 && right >= 2;
            return lines.stream().sorted(Comparator.comparingInt((Line l) -> columns && l.width < width*.72f ? (l.x > width*.4f ? 1 : 0) : 0)
                    .thenComparingDouble(l -> l.y).thenComparingDouble(l -> l.x)).toList();
        }
        private float avgY(List<Glyph> gs) { return (float)gs.stream().mapToDouble(g->g.y).average().orElse(0); }
    }

    private record Glyph(String text, int page, float x, float y, float width, float height, float size,
                         boolean bold, boolean italic, float pageWidth, float pageHeight) {}

    private static final class Line {
        final List<Glyph> glyphs; final int page; final float x,y,width,size,pageWidth,pageHeight;
        Line(List<Glyph> input) {
            glyphs = input.stream().sorted(Comparator.comparingDouble(g->g.x)).toList();
            Glyph g=glyphs.get(0); page=g.page; pageWidth=g.pageWidth; pageHeight=g.pageHeight;
            x=glyphs.stream().map(v->v.x).min(Float::compare).orElse(0f);
            y=(float)glyphs.stream().mapToDouble(v->v.y).average().orElse(0);
            float max=glyphs.stream().map(v->v.x+v.width).max(Float::compare).orElse(x); width=max-x;
            size=(float)glyphs.stream().mapToDouble(v->v.size).average().orElse(0);
        }
        String text() {
            StringBuilder text = new StringBuilder(); Glyph previous = null;
            for (Glyph glyph : glyphs) {
                appendInferredSpace(text, previous, glyph);
                text.append(glyph.text); previous = glyph;
            }
            return text.toString();
        }
        float maxX() { return x+width; }
        boolean margin() { return y < pageHeight*.1f || y > pageHeight*.9f; }
        double boldRatio() { return glyphs.stream().filter(g->g.bold).count()/(double)glyphs.size(); }
        String markdown() {
            StringBuilder out=new StringBuilder(); Boolean b=null,i=null; Glyph previous=null;
            for (Glyph g:glyphs) {
                boolean space = needsSpace(previous, g);
                if (b==null || b!=g.bold || i!=g.italic) {
                    if (b!=null) out.append(marker(b,i));
                    if (space) out.append(' ');
                    b=g.bold;i=g.italic; out.append(marker(b,i));
                } else if (space) {
                    out.append(' ');
                }
                out.append(g.text);
                previous=g;
            }
            if (b!=null) out.append(marker(b,i));
            return out.toString();
        }
        private String marker(boolean b, boolean i) { return b&&i?"***":b?"**":i?"*":""; }
        List<Float> cells() {
            List<Float> result=new ArrayList<>(); Glyph previous=null;
            for (Glyph g:glyphs) { if (previous==null || g.x-(previous.x+previous.width)>Math.max(18,size*1.8f)) result.add(g.x); previous=g; }
            return result;
        }
        List<String> cellMarkdown() {
            List<StringBuilder> cells=new ArrayList<>(); Glyph previous=null;
            for (Glyph g:glyphs) {
                boolean newCell = previous==null || g.x-(previous.x+previous.width)>Math.max(18,size*1.8f);
                if (newCell) cells.add(new StringBuilder());
                else appendInferredSpace(cells.get(cells.size()-1), previous, g);
                cells.get(cells.size()-1).append(g.text.replace("\\","\\\\").replace("|","\\|")); previous=g;
            }
            return cells.stream().map(StringBuilder::toString).toList();
        }
        private static void appendInferredSpace(StringBuilder target, Glyph previous, Glyph current) {
            if (needsSpace(previous, current)) target.append(' ');
        }
        private static boolean needsSpace(Glyph previous, Glyph current) {
            if (previous == null || previous.text.isEmpty() || current.text.isEmpty()
                    || Character.isWhitespace(previous.text.charAt(previous.text.length()-1))
                    || Character.isWhitespace(current.text.charAt(0))) return false;
            float gap = current.x - (previous.x + previous.width);
            float typicalWidth = Math.max(previous.width / Math.max(1, previous.text.length()),
                    current.width / Math.max(1, current.text.length()));
            return gap > Math.max(1.2f, typicalWidth * .45f);
        }
    }
}
