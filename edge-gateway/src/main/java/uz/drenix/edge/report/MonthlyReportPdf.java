package uz.drenix.edge.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.TeamReportRow;

/**
 * The monthly recruiting report, as a PDF.
 *
 * <p>This is a document of record: it is generated once a month, read by somebody senior, and
 * filed. It is laid out as one — a title block with a reference number, a summary the reader can
 * take in without scrolling, a table, a chart per recruiter, and a signed-off assessment.
 *
 * <p><b>Every figure appears beside its target.</b> A number on its own invites the reader to
 * supply their own baseline, and they will supply the wrong one. A bar against a target line
 * cannot be misread.
 *
 * <p><b>Unknown is drawn differently from zero.</b> Somebody the board could not match gets a dash
 * and a footnote, never a nought — the two look identical in a total and mean opposite things.
 */
public final class MonthlyReportPdf {

    private MonthlyReportPdf() {
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_TITLE =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    /**
     * @param month       the month reported on
     * @param preparedFor the lead the report is addressed to
     * @param subject     what the report is about: one recruiter's name, or the MS
     * @param ms          the company, always exactly one
     * @param assessment  the written assessment, or null when none could be produced
     */
    public static byte[] build(YearMonth month, String preparedFor, String subject, String ms,
                               List<TeamReportRow> rows, ActivityStandard standard,
                               String assessment, String assessmentNote) {

        String title = "Monthly Recruiting Performance Report";
        String period = month.format(MONTH_TITLE);

        try (Pdf pdf = new Pdf(title, period);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            titleBlock(pdf, title, month, period, preparedFor, subject, ms);
            summary(pdf, rows, standard);
            table(pdf, rows);
            perRecruiter(pdf, rows);
            assessment(pdf, assessment, assessmentNote);
            closing(pdf);

            pdf.paginate();
            pdf.document().save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the report", e);
        }
    }

    // ------------------------------------------------------------ title block

    private static void titleBlock(Pdf pdf, String title, YearMonth month, String period,
                                   String preparedFor, String subject, String ms)
            throws IOException {
        // A solid band across the head of the first page. It is the single strongest signal that
        // this is a document rather than a printout, and it costs one rectangle.
        pdf.rect(0, Pdf.PAGE_HEIGHT - 132f, Pdf.PAGE_WIDTH, 132f, Pdf.BAND);

        pdf.text(pdf.bold, 9f, new Color(0x9F, 0xB4, 0xD4), Pdf.MARGIN, Pdf.PAGE_HEIGHT - 46f,
                "DRENIX  ·  RECRUITING OPERATIONS");
        pdf.text(pdf.bold, 21f, Pdf.BAND_TEXT, Pdf.MARGIN, Pdf.PAGE_HEIGHT - 74f, title);
        pdf.text(pdf.regular, 12f, new Color(0xC8, 0xD6, 0xEA), Pdf.MARGIN,
                Pdf.PAGE_HEIGHT - 94f, period);

        pdf.textRight(pdf.regular, 8f, new Color(0x9F, 0xB4, 0xD4), Pdf.PAGE_WIDTH - Pdf.MARGIN,
                Pdf.PAGE_HEIGHT - 46f, "CONFIDENTIAL");
        pdf.textRight(pdf.bold, 9f, Pdf.BAND_TEXT, Pdf.PAGE_WIDTH - Pdf.MARGIN,
                Pdf.PAGE_HEIGHT - 60f, reference(month, subject));

        pdf.y(Pdf.PAGE_HEIGHT - 132f - 26f);

        // The provenance line. A report nobody can date or attribute is a report nobody trusts.
        String generated = java.time.Instant.now().atZone(ZoneOffset.UTC).format(STAMP);
        pdf.text(pdf.regular, 9f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                "Prepared for: " + preparedFor + "    ·    MS: " + ms
                + (subject.equals(ms) ? "" : "    ·    Subject: " + subject)
                + "    ·    Generated " + generated);
        pdf.move(10f);
        // Wrapped, not one line. It has run off the right edge twice now — once when a scope of
        // "JM and BP" was added to it and once when the MS was. A provenance line that is cut in
        // half is worse than no provenance line, because the reader cannot tell it was cut.
        String provenance = "Figures are drawn from RingCentral call records and the monday.com "
                            + "recruiting board for " + ms + " only. Hiring counts cover the "
                            + "reported month unless marked to date.";
        for (String line : pdf.wrap(pdf.regular, 8f, provenance, Pdf.CONTENT_WIDTH)) {
            pdf.text(pdf.regular, 8f, Pdf.MUTED, Pdf.MARGIN, pdf.y(), line);
            pdf.move(10f);
        }
        pdf.move(10f);
    }

    /**
     * A stable reference somebody can quote in an email.
     *
     * <p>Capped, because the scope of a single-recruiter report is that person's name and a long
     * one would run back along the title beneath it.
     */
    private static String reference(YearMonth month, String scope) {
        String tag = scope.replace(" and ", "-").replace(" ", "");
        if (tag.length() > 14) {
            tag = tag.substring(0, 14);
        }
        return "REF %s-%s".formatted(tag, month);
    }

    // ---------------------------------------------------------------- summary

    private static void summary(Pdf pdf, List<TeamReportRow> rows, ActivityStandard standard)
            throws IOException {
        heading(pdf, "1.  Summary");

        int calls = rows.stream().mapToInt(TeamReportRow::getCalls).sum();
        long callsTarget = rows.stream().mapToLong(TeamReportRow::getCallsTarget).sum();
        long talk = rows.stream().mapToLong(TeamReportRow::getTalkSeconds).sum();
        long talkTarget = rows.stream().mapToLong(TeamReportRow::getTalkSecondsTarget).sum();
        int sms = rows.stream().mapToInt(TeamReportRow::getSmsSent).sum();
        long smsTarget = rows.stream().mapToLong(TeamReportRow::getSmsSentTarget).sum();

        boolean anyBoard = rows.stream().anyMatch(TeamReportRow::getBoardMatched);
        int hired = rows.stream().mapToInt(TeamReportRow::getHiredInPeriod).sum();
        int kept = rows.stream().mapToInt(TeamReportRow::getStillActiveFromPeriod).sum();
        int lost = rows.stream().mapToInt(TeamReportRow::getTerminatedInPeriod).sum();

        // Six cards, two rows of three. Fixed geometry, because a summary that reflows depending
        // on the numbers in it is a summary nobody can compare month to month.
        float gap = 12f;
        float cardWidth = (Pdf.CONTENT_WIDTH - 2 * gap) / 3f;
        pdf.ensure(150f);

        card(pdf, 0, cardWidth, gap, "Recruiters", Integer.toString(rows.size()),
                rows.size() == 1 ? "in scope" : "in scope", null);
        card(pdf, 1, cardWidth, gap, "Calls", format(calls),
                "target " + format(callsTarget), ratio(calls, callsTarget));
        card(pdf, 2, cardWidth, gap, "Talk time", hours(talk),
                "target " + hours(talkTarget), ratio(talk, talkTarget));
        pdf.move(74f);
        card(pdf, 0, cardWidth, gap, "Messages sent", format(sms),
                "target " + format(smsTarget), ratio(sms, smsTarget));
        card(pdf, 1, cardWidth, gap, "Drivers hired", anyBoard ? Integer.toString(hired) : "—",
                anyBoard ? kept + " still driving" : "board unavailable", null);
        card(pdf, 2, cardWidth, gap, "Drivers lost", anyBoard ? Integer.toString(lost) : "—",
                anyBoard ? "terminated this month" : "board unavailable", null);
        pdf.move(88f);
    }

    private static void card(Pdf pdf, int column, float width, float gap, String label,
                             String value, String note, Double ratio) throws IOException {
        float x = Pdf.MARGIN + column * (width + gap);
        float bottom = pdf.y() - 62f;

        pdf.rect(x, bottom, width, 62f, Pdf.ZEBRA);
        // A single accent rule down the left edge. Enough to separate the cards without boxing
        // everything, which would fight the table below.
        pdf.rect(x, bottom, 2.5f, 62f, ratio == null ? Pdf.BAND : verdict(ratio));

        pdf.text(pdf.regular, 8f, Pdf.MUTED, x + 12f, bottom + 44f, label.toUpperCase(Locale.ROOT));
        pdf.text(pdf.bold, 19f, Pdf.INK, x + 12f, bottom + 22f, value);
        if (ratio != null) {
            pdf.textRight(pdf.bold, 11f, verdict(ratio), x + width - 12f, bottom + 24f,
                    Math.round(ratio * 100) + "%");
        }
        pdf.text(pdf.regular, 8f, Pdf.MUTED, x + 12f, bottom + 9f, note);
    }

    // ------------------------------------------------------------------ table

    private static final String[] COLUMNS =
            {"Recruiter", "Shifts", "Calls", "Talk", "Messages", "Hired", "Kept", "Lost"};
    private static final float[] WEIGHTS = {0.26f, 0.09f, 0.13f, 0.13f, 0.13f, 0.09f, 0.09f, 0.08f};

    private static void table(Pdf pdf, List<TeamReportRow> rows) throws IOException {
        heading(pdf, "2.  By recruiter");

        float[] x = new float[COLUMNS.length + 1];
        x[0] = Pdf.MARGIN;
        for (int i = 0; i < COLUMNS.length; i++) {
            x[i + 1] = x[i] + Pdf.CONTENT_WIDTH * WEIGHTS[i];
        }

        pdf.ensure(40f);
        header(pdf, x);

        boolean anyUnmatched = false;
        for (int i = 0; i < rows.size(); i++) {
            TeamReportRow row = rows.get(i);
            anyUnmatched |= !row.getBoardMatched();

            // Repeat the header when the table breaks: a page of unlabelled columns is unreadable.
            if (pdf.y() - 22f < 64f) {
                pdf.ensure(1000f);
                header(pdf, x);
            }

            float top = pdf.y();
            if (i % 2 == 1) {
                pdf.rect(Pdf.MARGIN, top - 20f, Pdf.CONTENT_WIDTH, 20f, Pdf.ZEBRA);
            }
            float base = top - 14f;

            pdf.text(pdf.bold, 9f, Pdf.INK, x[0] + 4f, base, row.getName());
            pdf.text(pdf.regular, 7f, Pdf.MUTED,
                    x[0] + 6f + pdf.width(pdf.bold, 9f, row.getName()), base,
                    row.getEntity().name().replace("ENTITY_", ""));

            cell(pdf, x[2], x[1], base, Integer.toString(row.getRecordedDays()), null);
            cell(pdf, x[3], x[2], base, format(row.getCalls()),
                    ratio(row.getCalls(), row.getCallsTarget()));
            cell(pdf, x[4], x[3], base, hours(row.getTalkSeconds()),
                    ratio(row.getTalkSeconds(), row.getTalkSecondsTarget()));
            cell(pdf, x[5], x[4], base, format(row.getSmsSent()),
                    ratio(row.getSmsSent(), row.getSmsSentTarget()));

            if (row.getBoardMatched()) {
                cell(pdf, x[6], x[5], base, Integer.toString(row.getHiredInPeriod()), null);
                cell(pdf, x[7], x[6], base, Integer.toString(row.getStillActiveFromPeriod()), null);
                cell(pdf, x[8], x[7], base, Integer.toString(row.getTerminatedInPeriod()), null);
            } else {
                cell(pdf, x[6], x[5], base, "—", null);
                cell(pdf, x[7], x[6], base, "—", null);
                cell(pdf, x[8], x[7], base, "—", null);
            }

            pdf.move(20f);
            pdf.line(Pdf.MARGIN, pdf.y(), Pdf.PAGE_WIDTH - Pdf.MARGIN, pdf.y(), Pdf.RULE, 0.4f);
        }

        pdf.move(12f);
        pdf.text(pdf.regular, 7.5f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                "Percentages are against the target for the shifts actually recorded, not against "
                + "the calendar month.");
        pdf.move(10f);
        if (anyUnmatched) {
            pdf.text(pdf.oblique, 7.5f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                    "A dash means the recruiting board held no record under that name. It is not a "
                    + "count of zero.");
            pdf.move(10f);
        }
        pdf.move(14f);
    }

    private static void header(Pdf pdf, float[] x) throws IOException {
        float top = pdf.y();
        pdf.rect(Pdf.MARGIN, top - 20f, Pdf.CONTENT_WIDTH, 20f, Pdf.BAND);
        float base = top - 14f;
        pdf.text(pdf.bold, 8f, Pdf.BAND_TEXT, x[0] + 4f, base, COLUMNS[0]);
        for (int i = 1; i < COLUMNS.length; i++) {
            pdf.textRight(pdf.bold, 8f, Pdf.BAND_TEXT, x[i + 1] - 4f, base, COLUMNS[i]);
        }
        pdf.move(20f);
    }

    /** A right-aligned figure, with its percentage of target under it when there is one. */
    private static void cell(Pdf pdf, float right, float left, float base, String value,
                             Double ratio) throws IOException {
        if (ratio == null) {
            pdf.textRight(pdf.regular, 9f, Pdf.INK, right - 4f, base, value);
            return;
        }
        String percent = Math.round(ratio * 100) + "%";
        pdf.textRight(pdf.regular, 9f, Pdf.INK, right - 4f - pdf.width(pdf.bold, 7.5f, percent) - 6f,
                base, value);
        pdf.textRight(pdf.bold, 7.5f, verdict(ratio), right - 4f, base, percent);
    }

    // ---------------------------------------------------------- per recruiter

    private static void perRecruiter(Pdf pdf, List<TeamReportRow> rows) throws IOException {
        heading(pdf, "3.  Against the standard");

        for (TeamReportRow row : rows) {
            // A block is a name, three activity bars, the recruiting standard and its three bars,
            // and a summary line. Never split across a page break: half a recruiter is unreadable.
            pdf.ensure(214f);

            pdf.text(pdf.bold, 11f, Pdf.INK, Pdf.MARGIN, pdf.y(), row.getName());
            String position = row.getPosition().isBlank() ? "HR" : row.getPosition();
            pdf.text(pdf.regular, 8f, Pdf.MUTED,
                    Pdf.MARGIN + pdf.width(pdf.bold, 11f, row.getName()) + 8f, pdf.y(),
                    row.getTenureStartMonth().isBlank()
                            ? position
                            : position + "  ·  " + row.getTenureMonths() + " months"
                              + ("INFERRED".equals(row.getTenureSource())
                                      ? " (tenure inferred from their first hire)" : ""));
            pdf.textRight(pdf.regular, 8f, Pdf.MUTED, Pdf.PAGE_WIDTH - Pdf.MARGIN, pdf.y(),
                    row.getRecordedDays() + " shifts recorded  ·  all three targets met on "
                    + row.getDaysAllTargetsMet());
            pdf.move(16f);

            bar(pdf, "Calls", row.getCalls(), row.getCallsTarget(), format(row.getCalls())
                    + " of " + format(row.getCallsTarget()));
            bar(pdf, "Talk time", row.getTalkSeconds(), row.getTalkSecondsTarget(),
                    hours(row.getTalkSeconds()) + " of " + hours(row.getTalkSecondsTarget()));
            bar(pdf, "Messages", row.getSmsSent(), row.getSmsSentTarget(),
                    format(row.getSmsSent()) + " of " + format(row.getSmsSentTarget()));

            hiringStandard(pdf, row);

            pdf.move(4f);
            String hiring = row.getBoardMatched()
                    ? "Hired %d this month, %d still driving, %d terminated.  To date: %d hired, "
                      .formatted(row.getHiredInPeriod(), row.getStillActiveFromPeriod(),
                              row.getTerminatedInPeriod(), row.getHiredToDate())
                      + row.getActiveNow() + " active."
                    : "No record on the recruiting board under this name; hiring figures are "
                      + "unavailable rather than zero.";
            pdf.text(row.getBoardMatched() ? pdf.regular : pdf.oblique, 8.5f, Pdf.MUTED,
                    Pdf.MARGIN, pdf.y(), hiring);
            pdf.move(22f);
        }
    }

    /**
     * The recruiting standard for the month, as a pair of bars.
     *
     * <p>Separate from the phone bars above and drawn differently on purpose. Calls and messages
     * are effort; hires and retained drivers are the result the company actually buys. Putting
     * them in one stack would invite the reader to average across the two, and a recruiter at 120%
     * of their call target and 20% of their hire target is not "70% overall" — they are failing at
     * the only part that counts.
     */
    private static void hiringStandard(Pdf pdf, TeamReportRow row) throws IOException {
        if (!row.getBoardMatched()) {
            return;
        }
        pdf.move(6f);

        if (row.getRecruitingQuarter() < 1) {
            pdf.text(pdf.oblique, 8f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                    "No driver on the board carries a hire date for this recruiter, so no quarter "
                    + "could be worked out and no target applies. "
                    + row.getActiveAtMonthEnd() + " active at month end.");
            pdf.move(14f);
            return;
        }

        // Past the first year the fourth quarter's target holds, and the heading says which
        // quarter of tenure this actually is so that "Quarter 4" for the third year running is not
        // mistaken for somebody who joined ten months ago.
        int quarter = row.getRecruitingQuarter();
        String heading = quarter <= 4
                ? "RECRUITING STANDARD  ·  QUARTER " + quarter + ", MONTH "
                  + row.getQuarterMonth() + " OF 3"
                : "RECRUITING STANDARD  ·  QUARTER 4 (CONTINUING, QUARTER " + quarter
                  + " OF TENURE)  ·  MONTH " + row.getQuarterMonth() + " OF 3";
        pdf.text(pdf.bold, 8f, Pdf.BAND, Pdf.MARGIN, pdf.y(), heading);
        pdf.move(13f);

        bar(pdf, "Hires, month", row.getHiredInPeriod(), row.getRequiredHiresInMonth(),
                row.getHiredInPeriod() + " of " + row.getRequiredHiresInMonth() + " required");

        // The quarter bar is measured against the whole quarter's target even in month one, and
        // the caption says how far in they are. A recruiter two months into a quarter is not
        // failing at 60% of it; the pace line is what says whether they are on course.
        int expected = Math.round(row.getRequiredHiresInQuarter() * row.getQuarterMonth() / 3f);
        bar(pdf, "Hires, quarter", row.getHiredInQuarter(), row.getRequiredHiresInQuarter(),
                row.getHiredInQuarter() + " of " + row.getRequiredHiresInQuarter()
                + ", pace " + expected);

        bar(pdf, "Active", row.getActiveAtMonthEnd(), row.getRequiredActive(),
                row.getActiveAtMonthEnd() + " of " + row.getRequiredActive() + " required");
    }

    /**
     * One horizontal bar against its target.
     *
     * <p>The track is the target and the fill is the actual, so a bar that reaches the end of the
     * track is a target met — no legend needed, and no way to read it backwards. Over-achievement
     * is capped at the track width and stated in the percentage instead, because a bar running off
     * the page would make everybody else's look broken.
     */
    private static void bar(Pdf pdf, String label, long actual, long target, String caption)
            throws IOException {
        // The caption sits beside the track, never on it. Drawn inside the bar it was grey text on
        // a red fill — legible in the layout code and not on the page.
        float labelWidth = 62f;
        float captionWidth = 104f;
        float percentWidth = 44f;
        float trackX = Pdf.MARGIN + labelWidth;
        float trackWidth = Pdf.CONTENT_WIDTH - labelWidth - captionWidth - percentWidth;
        float captionRight = Pdf.PAGE_WIDTH - Pdf.MARGIN - percentWidth;
        float top = pdf.y();
        float height = 9f;

        pdf.text(pdf.regular, 8.5f, Pdf.INK, Pdf.MARGIN, top - 8f, label);
        pdf.rect(trackX, top - height, trackWidth, height, Pdf.TRACK);

        if (target > 0) {
            double fraction = Math.min((double) actual / target, 1.0);
            double real = (double) actual / target;
            if (fraction > 0) {
                pdf.rect(trackX, top - height, (float) (trackWidth * fraction), height,
                        verdict(real));
            }
            pdf.textRight(pdf.bold, 8.5f, verdict(real), Pdf.PAGE_WIDTH - Pdf.MARGIN, top - 8f,
                    Math.round(real * 100) + "%");
        } else {
            pdf.textRight(pdf.regular, 8.5f, Pdf.MUTED, Pdf.PAGE_WIDTH - Pdf.MARGIN, top - 8f, "—");
        }

        pdf.textRight(pdf.regular, 7.5f, Pdf.MUTED, captionRight, top - 8f, caption);
        pdf.move(16f);
    }

    // ------------------------------------------------------------- assessment

    private static void assessment(Pdf pdf, String assessment, String note) throws IOException {
        heading(pdf, "4.  Assessment");

        if (assessment == null || assessment.isBlank()) {
            pdf.ensure(40f);
            pdf.text(pdf.oblique, 9f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                    note == null ? "No written assessment was produced for this report." : note);
            pdf.move(20f);
            return;
        }

        ReportMarkdown.render(pdf, assessment);
        pdf.move(6f);
    }

    private static void closing(Pdf pdf) throws IOException {
        pdf.ensure(56f);
        pdf.line(Pdf.MARGIN, pdf.y(), Pdf.PAGE_WIDTH - Pdf.MARGIN, pdf.y(), Pdf.RULE, 0.5f);
        pdf.move(14f);
        String disclaimer = "This report was generated automatically from recorded system data. "
                            + "The written assessment is machine-produced and should be read "
                            + "alongside the figures above, not in place of them.";
        for (String line : pdf.wrap(pdf.regular, 7.5f, disclaimer, Pdf.CONTENT_WIDTH)) {
            pdf.text(pdf.regular, 7.5f, Pdf.MUTED, Pdf.MARGIN, pdf.y(), line);
            pdf.move(10f);
        }
        pdf.move(1f);
        pdf.text(pdf.regular, 7.5f, Pdf.MUTED, Pdf.MARGIN, pdf.y(),
                "Drenix Recruiting Operations  ·  Confidential  ·  Not for external distribution");
    }

    // ----------------------------------------------------------------- shared

    private static void heading(Pdf pdf, String text) throws IOException {
        pdf.ensure(46f);
        pdf.text(pdf.bold, 12.5f, Pdf.BAND, Pdf.MARGIN, pdf.y(), text);
        pdf.move(7f);
        pdf.line(Pdf.MARGIN, pdf.y(), Pdf.PAGE_WIDTH - Pdf.MARGIN, pdf.y(), Pdf.BAND, 1.2f);
        pdf.move(18f);
    }

    /** Green at or above target, red below. The only colour coding in the document. */
    private static Color verdict(double ratio) {
        return ratio >= 1.0 ? Pdf.GOOD : Pdf.SHORT;
    }

    private static Double ratio(long actual, long target) {
        return target <= 0 ? null : (double) actual / target;
    }

    private static String format(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    /** Talk time reads as hours and minutes; a five-digit second count means nothing to anybody. */
    private static String hours(long seconds) {
        long totalMinutes = Math.round(seconds / 60.0);
        return totalMinutes < 60
                ? totalMinutes + "m"
                : "%dh %02dm".formatted(totalMinutes / 60, totalMinutes % 60);
    }
}
