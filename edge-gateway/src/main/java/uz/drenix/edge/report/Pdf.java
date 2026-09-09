package uz.drenix.edge.report;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A cursor down an A4 page, and the drawing primitives the report is built from.
 *
 * <p>PDFBox has no concept of flow: every string is placed at a coordinate, and running off the
 * bottom of the page produces a document with text underneath the paper rather than an error. This
 * class is the thing that stops that happening — it tracks where the last thing was drawn, breaks
 * the page when the next thing will not fit, and repaints the running header and footer when it
 * does.
 *
 * <p>Everything below is deliberately small and shared. A report where the third table has a
 * different row height from the first looks like a mistake even when the numbers are right.
 */
final class Pdf implements AutoCloseable {

    /** The house palette. Dark navy reads as official; the accents are used sparingly. */
    static final Color INK = new Color(0x1A, 0x1F, 0x2B);
    static final Color MUTED = new Color(0x6B, 0x72, 0x84);
    static final Color RULE = new Color(0xD6, 0xDA, 0xE3);
    static final Color BAND = new Color(0x14, 0x2A, 0x4A);
    static final Color BAND_TEXT = new Color(0xFF, 0xFF, 0xFF);
    static final Color GOOD = new Color(0x1B, 0x7F, 0x4B);
    static final Color SHORT = new Color(0xB3, 0x2D, 0x2D);
    static final Color TRACK = new Color(0xEC, 0xEF, 0xF4);
    static final Color ZEBRA = new Color(0xF7, 0xF9, 0xFC);

    static final float MARGIN = 48f;
    static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    /** Where the body starts, below the running header. */
    private static final float TOP = PAGE_HEIGHT - 96f;
    private static final float BOTTOM = 64f;

    final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    final PDType1Font oblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private final PDDocument document = new PDDocument();
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream stream;
    private float y;

    /** Repainted at the top and bottom of every page after the first. */
    private final String runningTitle;
    private final String runningPeriod;

    Pdf(String runningTitle, String runningPeriod) throws IOException {
        this.runningTitle = runningTitle;
        this.runningPeriod = runningPeriod;
        newPage(false);
    }

    PDDocument document() {
        return document;
    }

    float y() {
        return y;
    }

    void y(float value) {
        y = value;
    }

    void move(float down) {
        y -= down;
    }

    /**
     * Breaks the page if {@code needed} points of vertical space are not left.
     *
     * <p>Called before drawing anything that must not be split — a table row, a chart, a heading
     * and the line under it. A heading stranded alone at the foot of a page is the single most
     * common way a generated document looks generated.
     */
    void ensure(float needed) throws IOException {
        if (y - needed < BOTTOM) {
            newPage(true);
        }
    }

    private void newPage(boolean running) throws IOException {
        if (stream != null) {
            stream.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        pages.add(page);
        stream = new PDPageContentStream(document, page);
        y = running ? TOP : PAGE_HEIGHT - MARGIN;

        if (running) {
            text(regular, 8f, MUTED, MARGIN, PAGE_HEIGHT - 34f, runningTitle);
            textRight(regular, 8f, MUTED, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 34f, runningPeriod);
            line(MARGIN, PAGE_HEIGHT - 42f, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 42f, RULE, 0.5f);
        }
    }

    // ---------------------------------------------------------------- drawing

    void text(PDType1Font font, float size, Color colour, float x, float baseline, String value)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(colour);
        stream.newLineAtOffset(x, baseline);
        stream.showText(sanitise(value));
        stream.endText();
    }

    void textRight(PDType1Font font, float size, Color colour, float right, float baseline,
                   String value) throws IOException {
        text(font, size, colour, right - width(font, size, value), baseline, value);
    }

    void textCentred(PDType1Font font, float size, Color colour, float centre, float baseline,
                     String value) throws IOException {
        text(font, size, colour, centre - width(font, size, value) / 2f, baseline, value);
    }

    void line(float x1, float y1, float x2, float y2, Color colour, float thickness)
            throws IOException {
        stream.setStrokingColor(colour);
        stream.setLineWidth(thickness);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    void rect(float x, float bottom, float width, float height, Color fill) throws IOException {
        stream.setNonStrokingColor(fill);
        stream.addRect(x, bottom, width, height);
        stream.fill();
    }

    float width(PDType1Font font, float size, String value) {
        try {
            return font.getStringWidth(sanitise(value)) / 1000f * size;
        } catch (IOException e) {
            // Only thrown for glyphs the font cannot measure, which sanitise has already removed.
            return value.length() * size * 0.5f;
        }
    }

    /**
     * Wraps a paragraph to a width, returning the lines.
     *
     * <p>Word-boundary only: hyphenating a driver's name or a percentage would be worse than a
     * ragged right edge.
     */
    List<String> wrap(PDType1Font font, float size, String value, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(font, size, candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * The Standard 14 fonts are WinAnsi, and a character outside it makes PDFBox throw halfway
     * through writing the file. Names arrive from monday.com and from whoever typed them, so this
     * is not hypothetical — an em dash in a title used to be enough.
     */
    private static String sanitise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(switch (c) {
                case '—', '–' -> '-';
                case '‘', '’' -> '\'';
                case '“', '”' -> '"';
                case '…' -> "...";
                default -> c >= 32 && c <= 255 ? c : '?';
            });
        }
        return out.toString();
    }

    /** Page numbers, written once at the end because until then there is no total to write. */
    void paginate() throws IOException {
        stream.close();
        stream = null;
        for (int i = 0; i < pages.size(); i++) {
            try (PDPageContentStream footer = new PDPageContentStream(
                    document, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                String label = "Page %d of %d".formatted(i + 1, pages.size());
                float x = PAGE_WIDTH - MARGIN - width(regular, 8f, label);
                footer.beginText();
                footer.setFont(regular, 8f);
                footer.setNonStrokingColor(MUTED);
                footer.newLineAtOffset(x, 36f);
                footer.showText(label);
                footer.endText();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
        document.close();
    }
}
