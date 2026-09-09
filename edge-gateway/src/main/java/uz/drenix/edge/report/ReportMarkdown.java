package uz.drenix.edge.report;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Lays the written assessment out as part of the document rather than as a wall of text.
 *
 * <p>The assessment used to be four paragraphs, and stripping the markdown off it and wrapping the
 * remainder was enough. It is now a ten-section management report with a fifteen-column scorecard,
 * a financial table and an action plan, and the same treatment would produce pages of pipe
 * characters — a table flattened into prose is not a table, it is unreadable.
 *
 * <p>So this understands a deliberately small grammar, and the prompt that produces the text names
 * exactly the same one: {@code ## } and {@code ### } headings, pipe tables, {@code - } bullets,
 * {@code **bold**} inline, and paragraphs. Anything else falls through to being drawn as a
 * paragraph, which is the failure that costs the reader least.
 *
 * <p><b>Tables shrink rather than overflow.</b> A fifteen-column scorecard on A4 gives each column
 * about thirty points, so the type size is chosen from the column count and long cells are cut
 * with an ellipsis. A column that runs off the page edge takes the next one with it and the row
 * stops being readable at all; a truncated cell costs one word.
 */
final class ReportMarkdown {

    private ReportMarkdown() {
    }

    /** Amber. Only used for the YELLOW status, which is neither of the other two. */
    private static final Color WARN = new Color(0xB5, 0x7A, 0x0A);

    static void render(Pdf pdf, String markdown) throws IOException {
        List<String> lines = new ArrayList<>(List.of(markdown.replace("\r\n", "\n").split("\n")));
        List<String> paragraph = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();

            if (line.isEmpty()) {
                paragraph = flush(pdf, paragraph);
                continue;
            }
            if (line.startsWith("## ") || line.startsWith("# ")) {
                paragraph = flush(pdf, paragraph);
                section(pdf, line.replaceFirst("^#+\\s*", "").replace("**", "").strip());
                continue;
            }
            if (line.startsWith("### ") || line.startsWith("#### ")) {
                paragraph = flush(pdf, paragraph);
                subsection(pdf,
                        line.replaceFirst("^#+\\s*", "").replace("**", "").strip());
                continue;
            }
            if (line.startsWith("|") && i + 1 < lines.size() && isSeparator(lines.get(i + 1))) {
                paragraph = flush(pdf, paragraph);
                List<String> block = new ArrayList<>();
                block.add(line);
                int j = i + 2;
                while (j < lines.size() && lines.get(j).strip().startsWith("|")) {
                    block.add(lines.get(j).strip());
                    j++;
                }
                table(pdf, block);
                i = j - 1;
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                paragraph = flush(pdf, paragraph);
                bullet(pdf, line.substring(2).strip());
                continue;
            }
            if (line.matches("^[-*_]{3,}$")) {
                paragraph = flush(pdf, paragraph);
                continue;
            }
            paragraph.add(line);
        }
        flush(pdf, paragraph);
    }

    // --------------------------------------------------------------- headings

    /** A lettered section of the report: "A. EXECUTIVE SUMMARY". */
    private static void section(Pdf pdf, String text) throws IOException {
        pdf.ensure(52f);
        pdf.move(8f);
        pdf.text(pdf.bold, 10.5f, Pdf.BAND, Pdf.MARGIN, pdf.y(), text.toUpperCase(Locale.ENGLISH));
        pdf.move(6f);
        pdf.line(Pdf.MARGIN, pdf.y(), Pdf.PAGE_WIDTH - Pdf.MARGIN, pdf.y(), Pdf.RULE, 0.8f);
        pdf.move(15f);
    }

    /** One person inside section C. */
    private static void subsection(Pdf pdf, String text) throws IOException {
        pdf.ensure(40f);
        pdf.move(4f);
        pdf.text(pdf.bold, 9.5f, Pdf.INK, Pdf.MARGIN, pdf.y(), text);
        pdf.move(14f);
    }

    // ------------------------------------------------------------- paragraphs

    private static List<String> flush(Pdf pdf, List<String> paragraph) throws IOException {
        if (!paragraph.isEmpty()) {
            paragraphOf(pdf, String.join(" ", paragraph), Pdf.MARGIN, Pdf.CONTENT_WIDTH);
            pdf.move(6f);
        }
        return new ArrayList<>();
    }

    private static void bullet(Pdf pdf, String text) throws IOException {
        float indent = 12f;
        pdf.ensure(14f);
        pdf.text(pdf.regular, 9f, Pdf.BAND, Pdf.MARGIN + 2f, pdf.y(), "-");
        paragraphOf(pdf, text, Pdf.MARGIN + indent, Pdf.CONTENT_WIDTH - indent);
        pdf.move(2f);
    }

    /**
     * A paragraph, drawn as a sequence of runs so that {@code **bold**} really is bold.
     *
     * <p>The individual analyses lean on it: every one of them starts a paragraph with a label —
     * Results, Weaknesses, Recommended action — and a page where those labels sit at the same
     * weight as the sentences after them is a page nobody skims successfully.
     */
    private static void paragraphOf(Pdf pdf, String text, float left, float width)
            throws IOException {
        float size = 9f;
        float x = left;
        pdf.ensure(14f);

        for (Run run : runs(text)) {
            PDType1Font font = run.bold() ? pdf.bold : pdf.regular;
            for (String word : run.text().split("(?<= )|(?= )")) {
                if (word.isEmpty()) {
                    continue;
                }
                float advance = pdf.width(font, size, word);
                if (x + advance > left + width && x > left) {
                    pdf.move(12f);
                    pdf.ensure(14f);
                    x = left;
                    if (word.isBlank()) {
                        continue;
                    }
                }
                pdf.text(font, size, colourFor(word, Pdf.INK), x, pdf.y(), word);
                x += advance;
            }
        }
        pdf.move(12f);
    }

    private record Run(String text, boolean bold) {
    }

    /** Splits on double asterisks. Odd segments are the bold ones. */
    private static List<Run> runs(String text) {
        List<Run> runs = new ArrayList<>();
        String[] parts = text.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                runs.add(new Run(parts[i].replace("*", "").replace("`", ""), i % 2 == 1));
            }
        }
        return runs;
    }

    // ----------------------------------------------------------------- tables

    private static boolean isSeparator(String line) {
        String stripped = line.strip();
        return stripped.startsWith("|") && stripped.matches("\\|[\\s:|-]+\\|?");
    }

    private static void table(Pdf pdf, List<String> block) throws IOException {
        List<String[]> parsed = new ArrayList<>();
        int columns = 0;
        for (String line : block) {
            String[] cells = cells(line);
            columns = Math.max(columns, cells.length);
            parsed.add(cells);
        }
        if (parsed.size() < 2 || columns == 0) {
            return;
        }

        // Fifteen columns on A4 is thirty points each. Type size is the only thing that can give,
        // and a scorecard nobody can read is worth less than a small one.
        float size = columns <= 6 ? 8f : columns <= 10 ? 7f : 6f;
        float[] widths = widths(pdf, parsed, columns, size);

        float[] x = new float[columns + 1];
        x[0] = Pdf.MARGIN;
        for (int i = 0; i < columns; i++) {
            x[i + 1] = x[i] + widths[i];
        }

        pdf.move(4f);
        String[] header = parsed.getFirst();
        List<String[]> body = parsed.subList(1, parsed.size());

        pdf.ensure(46f);
        tableHeader(pdf, header, x, columns, size);

        // A narrow table can afford to wrap a long cell over two lines; the fifteen-column
        // scorecard cannot, and its cells are short enough not to need it. Clipping the action
        // plan was losing the half of "Immediate coaching and performance review" that says what
        // to do, which is the whole point of that column.
        int maxLines = columns <= 8 ? 2 : 1;

        for (int r = 0; r < body.size(); r++) {
            String[] row = body.get(r);

            List<List<String>> cells = new ArrayList<>(columns);
            int deepest = 1;
            for (int c = 0; c < columns; c++) {
                String cell = c < row.length ? row[c] : "";
                List<String> lines = cell.isEmpty()
                        ? List.of()
                        : pdf.wrap(pdf.regular, size, cell, widths[c] - 6f);
                if (lines.size() > maxLines) {
                    lines = new ArrayList<>(lines.subList(0, maxLines));
                    lines.set(maxLines - 1,
                            clip(pdf, pdf.regular, size, cell.substring(
                                    Math.min(cell.length(),
                                            String.join(" ", lines.subList(0, maxLines - 1))
                                                    .length() + 1)),
                                    widths[c] - 6f));
                }
                cells.add(lines);
                deepest = Math.max(deepest, lines.size());
            }

            float height = 6f + deepest * (size + 3f);
            if (pdf.y() - height < 70f) {
                pdf.ensure(1000f);
                tableHeader(pdf, header, x, columns, size);
            }
            float top = pdf.y();
            if (r % 2 == 1) {
                pdf.rect(Pdf.MARGIN, top - height, x[columns] - Pdf.MARGIN, height, Pdf.ZEBRA);
            }
            for (int c = 0; c < columns; c++) {
                List<String> lines = cells.get(c);
                for (int l = 0; l < lines.size(); l++) {
                    float base = top - 3f - (l + 1) * (size + 3f) + 3f;
                    String line = lines.get(l);
                    Color colour = colourFor(line, Pdf.INK);
                    if (numeric(line) && lines.size() == 1) {
                        pdf.textRight(pdf.regular, size, colour, x[c + 1] - 3f, base, line);
                    } else {
                        pdf.text(pdf.regular, size, colour, x[c] + 3f, base, line);
                    }
                }
            }
            pdf.move(height);
            pdf.line(Pdf.MARGIN, pdf.y(), x[columns], pdf.y(), Pdf.RULE, 0.3f);
        }
        pdf.move(12f);
    }

    private static void tableHeader(Pdf pdf, String[] header, float[] x, int columns, float size)
            throws IOException {
        // Two lines of header, because "Opportunity Gap" over a thirty-point column has to break
        // somewhere and truncating it to "Opport.." loses the only thing that names the number.
        List<List<String>> wrapped = new ArrayList<>();
        int deepest = 1;
        for (int c = 0; c < columns; c++) {
            String label = c < header.length ? header[c] : "";
            List<String> lines = pdf.wrap(pdf.bold, size, label, x[c + 1] - x[c] - 6f);
            if (lines.size() > 2) {
                lines = lines.subList(0, 2);
            }
            wrapped.add(lines);
            deepest = Math.max(deepest, lines.size());
        }

        float height = 6f + deepest * (size + 2f);
        float top = pdf.y();
        pdf.rect(Pdf.MARGIN, top - height, x[columns] - Pdf.MARGIN, height, Pdf.BAND);
        for (int c = 0; c < columns; c++) {
            List<String> lines = wrapped.get(c);
            for (int l = 0; l < lines.size(); l++) {
                float base = top - 4f - (l + 1) * (size + 2f) + 2f;
                pdf.text(pdf.bold, size, Pdf.BAND_TEXT, x[c] + 3f, base,
                        clip(pdf, pdf.bold, size, lines.get(l), x[c + 1] - x[c] - 6f));
            }
        }
        pdf.move(height);
    }

    /**
     * Column widths, proportional to what is in them and never below a floor.
     *
     * <p>Sized from the widest cell rather than evenly, because an even split gives "Status" the
     * same room as "Action" and one of them then reads as gibberish. The floor stops a column of
     * short numbers collapsing to nothing.
     */
    private static float[] widths(Pdf pdf, List<String[]> rows, int columns, float size) {
        float[] wanted = new float[columns];
        float total = 0f;
        float[] floors = new float[columns];
        for (int c = 0; c < columns; c++) {
            float widest = 18f;
            for (String[] row : rows) {
                if (c < row.length) {
                    widest = Math.max(widest, pdf.width(pdf.bold, size, row[c]) + 8f);
                }
            }
            // Beyond this a single verbose cell would starve every other column.
            wanted[c] = Math.min(widest, Pdf.CONTENT_WIDTH * 0.30f);
            // No column may end up narrower than the longest single word in its heading. A header
            // is one or two words and cannot be shortened without losing what the column is; a
            // "Prior.." above a column of P1s tells the reader nothing.
            floors[c] = c < rows.getFirst().length ? longestWord(pdf, size, rows.getFirst()[c]) : 18f;
            total += wanted[c];
        }
        float scale = Pdf.CONTENT_WIDTH / total;
        float claimed = 0f;
        for (int c = 0; c < columns; c++) {
            wanted[c] = Math.max(wanted[c] * scale, floors[c]);
            claimed += wanted[c];
        }
        // Raising the narrow columns to their floor may have overshot the page. Take it back from
        // the columns that are above their own floor, in proportion to their slack.
        if (claimed > Pdf.CONTENT_WIDTH) {
            float excess = claimed - Pdf.CONTENT_WIDTH;
            float slack = 0f;
            for (int c = 0; c < columns; c++) {
                slack += Math.max(0f, wanted[c] - floors[c]);
            }
            if (slack > 0f) {
                for (int c = 0; c < columns; c++) {
                    float own = Math.max(0f, wanted[c] - floors[c]);
                    wanted[c] -= excess * (own / slack);
                }
            }
        }
        return wanted;
    }

    /** The width of the longest unbreakable word in a heading, plus its padding. */
    private static float longestWord(Pdf pdf, float size, String label) {
        float widest = 18f;
        for (String word : label.split("\\s+")) {
            widest = Math.max(widest, pdf.width(pdf.bold, size, word) + 8f);
        }
        return widest;
    }

    private static String[] cells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].strip().replace("**", "").replace("`", "");
        }
        return cells;
    }

    private static String clip(Pdf pdf, PDType1Font font, float size, String value, float max) {
        if (pdf.width(font, size, value) <= max) {
            return value;
        }
        String candidate = value;
        while (candidate.length() > 1 && pdf.width(font, size, candidate + "..") > max) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate.strip() + "..";
    }

    private static boolean numeric(String cell) {
        return cell.matches("^[-+(]?[0-9][0-9,.\\s/]*%?\\)?$")
               || cell.matches("^[0-9]+\\s*/\\s*[0-9]+$");
    }

    /**
     * The traffic light, in the colour it names.
     *
     * <p>The brief asks for GREEN, YELLOW and RED as words rather than as the coloured circles a
     * chat window would show, because the Standard 14 fonts have no emoji and every one of them
     * would print as a question mark. Colouring the word restores what the emoji was for.
     */
    private static Color colourFor(String text, Color fallback) {
        // Case-sensitive on purpose. The brief asks for the statuses in capitals, and matching
        // loosely would tint every "green light" and "critical path" in the prose as well.
        String token = text.strip().replaceAll("[^A-Za-z]", "");
        return switch (token) {
            case "GREEN", "HEALTHY" -> Pdf.GOOD;
            case "RED", "CRITICAL" -> Pdf.SHORT;
            case "YELLOW" -> WARN;
            default -> fallback;
        };
    }
}
