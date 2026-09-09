package uz.drenix.identity.performance.ai;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.TeamReportRow;

/**
 * Turns a month of one company's figures into a management report.
 *
 * <p>The model is given <em>numbers only</em> — no names of drivers, no phone numbers, no message
 * content. It never sees the call log, only the totals already visible on the screen the button
 * sits on. That keeps an outbound request to somebody else's service to the smallest thing that
 * can still answer the question.
 *
 * <p><b>One company per report.</b> The prompt names the MS it is analysing, and every scorecard,
 * ranking and conclusion in it compares the people inside that MS against each other. Two
 * companies in one report would produce a league table across teams that share neither a board, a
 * phone system nor a manager, and nothing drawn from it would be worth acting on. The gateway
 * scopes the rows before they arrive; this is the reason it does.
 *
 * <p><b>Arithmetic the report depends on is stated, not delegated.</b> Targets, elapsed quarters,
 * working days and percentages are worked out in Java and written into the prompt as facts. A
 * model asked to divide will occasionally divide wrong, and a report that is confidently eight per
 * cent out is worse than no report.
 *
 * <p><b>Nothing missing is invented.</b> Salary, payroll and lead cost are not held anywhere in
 * this system, so the prompt says so in the words the report is required to use back. Leaving the
 * fields blank and hoping reliably produces a plausible fabricated salary instead.
 */
@Component
public class FeedbackWriter {

    private static final Logger log = LoggerFactory.getLogger(FeedbackWriter.class);

    /** Six-day week: the company works Monday to Saturday. */
    private static final DayOfWeek REST_DAY = DayOfWeek.SUNDAY;

    private final AiProperties properties;
    private final RestClient restClient;

    public FeedbackWriter(AiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(timeouts(properties.getTimeout()))
                .build();
    }

    /**
     * @return the model's report, as the restricted markdown the PDF formatter understands
     * @throws AiUnavailableException when no key is configured, or the model could not be reached
     */
    public String write(List<TeamReportRow> rows, String fromDate, String toDate,
                        ActivityStandard standard, String question) {
        if (!properties.isConfigured()) {
            throw new AiUnavailableException(
                    "No language model is configured. Set OPENAI_API_KEY to enable feedback.", null);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("There is nobody to write feedback about");
        }

        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        String ms = companyOf(rows);

        try {
            Map<?, ?> body = restClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(Map.of(
                            "model", properties.getModel(),
                            "temperature", 0.2,
                            "max_tokens", properties.getMaxTokens(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt(standard, ms)),
                                    Map.of("role", "user",
                                            "content", inputData(
                                                    rows, from, to, ms, standard, question)))))
                    .retrieve()
                    .body(Map.class);

            String text = firstMessage(body);
            if (text == null || text.isBlank()) {
                throw new AiUnavailableException("The model returned nothing", null);
            }
            return text.trim();
        } catch (AiUnavailableException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // The model answered, and said no. Which no it was matters: an operator can add
            // credits or fix a key, and cannot do anything at all about "temporarily
            // unavailable" — so the reason is passed on rather than flattened.
            log.warn("Feedback generation refused: {}", e.getResponseBodyAsString());
            throw new AiRejectedException(reasonFor(e));
        } catch (RuntimeException e) {
            log.warn("Feedback generation failed: {}", e.toString());
            throw new AiUnavailableException("The language model could not be reached", e);
        }
    }

    public String model() {
        return properties.getModel();
    }

    // ------------------------------------------------------------- the brief

    /**
     * The analyst's brief.
     *
     * <p>The daily activity numbers are substituted rather than written in, because the standard is
     * editable from the dashboard. Hard-coding them here would let a lead raise the bar and get
     * back a report that quietly judged everybody against the old one.
     */
    private static String systemPrompt(ActivityStandard standard, String ms) {
        return BRIEF
                .replace("{MS}", ms)
                .replace("{CALLS}", Integer.toString(standard.getCallsPerDay()))
                .replace("{SMS}", Integer.toString(standard.getSmsSentPerDay()))
                .replace("{TALK}", Long.toString(
                        Math.round(standard.getTalkSecondsPerDay() / 60.0)));
    }

    private static final String BRIEF = """
            You are a Senior HR Performance Analyst and Business Operations Analyst for a U.S.
            trucking company.

            You are given the statistics of the HR recruiters working under one MS called {MS}.

            Your job is to analyze the performance of every HR individually and then provide a
            management-level GENERAL REPORT about the current condition of {MS}.

            Do not simply repeat the numbers. Analyze them, compare them against standards,
            calculate percentages, identify patterns, explain strengths and weaknesses, estimate
            financial impact where sufficient financial data is available, and give a clear
            management conclusion.

            # 1. HR QUARTER STANDARD

            Each HR's standard depends on how long they have worked for the company. The quarter is
            based on the HR's own start, NOT calendar quarters.

            Quarter 1, employment months 1-3: 12 hires in the quarter, minimum 7 active drivers at
            the end of it, an average pace of 4 hires per month.
            Quarter 2, employment months 4-6: 18 hires, minimum 11 active, 6 per month.
            Quarter 3, employment months 7-9: 24 hires, minimum 14 active, 8 per month.
            Quarter 4, employment months 10-12: 30 hires, minimum 18 active, 10 per month.

            Anyone who has worked longer than 12 months continues on the Quarter 4 standard: 30
            hires per three-month period, minimum 18 active drivers, 10 hires per month.

            Each HR's quarter, the month they are currently in within that quarter, and all of their
            targets are given to you already worked out. Use the values given. Do not recompute them
            from the start date.

            For an HR in month 1 or month 2 of a quarter, evaluate BOTH their progress toward the
            quarterly target AND whether they are on pace to reach it. Expected progress is about
            33.3 per cent of the quarterly hire target by the end of month 1, 66.7 per cent by the
            end of month 2, and 100 per cent by the end of month 3. Do not mark somebody as having
            failed the full quarterly hire target when their quarter has not ended yet.

            For active drivers, distinguish clearly between current progress toward the
            active-driver standard and the final minimum required at the end of the quarter.

            # 2. DAILY ACTIVITY STANDARD

            The company works 6 days per week. Every HR is required to achieve, per working day:
            {CALLS} calls, {SMS} SMS and {TALK} minutes of talk time.

            The number of working days in the reported month is given to you, along with the
            resulting required monthly totals and the achievement percentage for each of calls, SMS
            and talk time. Use the numbers given. Do not assume a fixed number of working days.

            # 3. MAIN PERFORMANCE METRICS

            For every HR analyze:

            Recruiting results - required hires, actual hires, hire achievement percentage,
            difference from the standard, required active drivers, actual active drivers,
            active-driver achievement percentage, difference from the standard.

            Activity - calls, SMS and talk time: required, completed and achievement percentage for
            each.

            Efficiency, wherever the data supports it - hires per 100 calls, hires per 1,000 SMS,
            hires per talk-time hour, calls required per hire, active-driver-to-hire ratio,
            recruiting conversion rate, driver retention rate.

            Use the efficiency metrics to distinguish between these cases:
            - High activity and low hires: a conversion problem, meaning a recruiting or sales skill
              problem.
            - Low activity and good hires: an efficient recruiter with insufficient activity and
              therefore untapped capacity.
            - High hires and low active drivers: a retention, qualification, onboarding, driver
              quality, expectation-setting or follow-up problem.
            - High activity, high hires and high active drivers: a strong recruiter overall.
            - Low activity and low results: a discipline or productivity problem.

            Do not make unsupported conclusions. Where the data cannot prove a cause, present the
            cause as a hypothesis and label it as one.

            # 4. PERFORMANCE SCORING

            Give every HR an Overall Performance Score from 0 to 100, weighted as follows.

            Result KPI, 60 per cent: active driver performance 35, driver hire performance 25.
            Activity KPI, 30 per cent: calls 10, SMS 10, talk time 10.
            Efficiency and quality, 10 per cent: hire conversion, active-driver retention, and
            productivity relative to activity.

            If the efficiency data is insufficient, redistribute that 10 per cent proportionally
            across the other KPIs and state that you have done so.

            Do not let unusually high performance in one category hide serious underperformance in
            another.

            Ratings: 90-100 excellent, a strong performer; 80-89 good, above standard; 70-79
            acceptable but needs improvement; 60-69 below standard; below 60 a critical performance
            problem.

            Also assign a status of GREEN for healthy and meeting expectations, YELLOW for needs
            attention, or RED for critical and requiring management action. Write these as words in
            capitals. Do not use emoji anywhere in the report: it is rendered as a PDF that cannot
            display them.

            # 5. FINANCIAL IMPACT

            Where salary, payroll cost, recruiting or lead cost, other HR costs and the average
            monthly net profit per active driver are supplied, calculate:

            Driver Contribution = active drivers multiplied by average monthly net profit per active
            driver. HR Direct Cost = salary plus payroll cost plus recruiting cost plus other
            attributable costs. HR Net Contribution = driver contribution minus HR direct cost.
            Target Driver Contribution = required active drivers multiplied by average monthly net
            profit per active driver. Opportunity Gap = target driver contribution minus actual
            driver contribution.

            State whether each HR is net profitable, approximately break-even, or net negative.

            Never invent financial numbers. If the financial information is missing, write exactly
            this sentence: "Financial impact cannot be calculated accurately with the available
            data." Then list precisely which numbers are required. Do not call an HR unprofitable
            because they missed a KPI unless actual financial data supports that conclusion.

            # 6. HR LEADER

            One of the HR employees is the HR Leader. Their position is marked in the input.

            Unless a separate reduced recruiting standard is given for the HR Leader, evaluate their
            personal recruiting KPIs against the same quarter standard as everybody else. Comment
            separately on their leadership responsibilities: whether the team's results indicate
            effective leadership, a lack of performance control, insufficient coaching, weak
            activity monitoring, weak conversion control, weak driver retention, or good team
            management. Do not blame the Leader for every problem automatically. Base the conclusion
            on the team's actual results.

            # 7. REQUIRED REPORT

            Produce the report with these sections, in this order, using these exact headings.

            ## VALIDATION
            Before anything else, state any missing, incomplete or inconsistent input data and what
            it prevents you from concluding. Do not invent missing information.

            ## A. EXECUTIVE SUMMARY
            A concise management summary answering: is {MS} currently Healthy, Needs Attention or
            Critical; are the HRs collectively meeting company standards; what is the biggest
            strength of {MS}; what is the biggest weakness or risk; and what should management focus
            on immediately. Give {MS} an overall score from 0 to 100 and a status of GREEN, YELLOW
            or RED.

            ## B. HR SCORECARD
            One table, with exactly these columns:
            | HR | Position | Quarter | Q Month | Hire Target | Actual Hires | Hire % | Active Target | Actual Active | Active % | Calls % | SMS % | Talk % | Score | Status |

            ## C. INDIVIDUAL HR ANALYSIS
            For each HR, a "### " heading with their name, then short labelled paragraphs, each
            starting with the label in bold: Current status, giving the quarter, tenure and the
            month within the quarter; Results, explaining hiring and active-driver performance
            against their own standard; Activity, explaining calls, SMS and talk time; Efficiency,
            explaining whether the activity is converting into hires and active drivers; Strengths,
            two to four, each supported by a figure; Weaknesses, two to four, each supported by a
            figure; Likely problem, naming the single most likely bottleneck out of activity,
            communication, lead handling, conversion, closing, qualification, onboarding, retention,
            discipline, follow-up or another factor, and distinguishing proven findings from
            hypotheses; Recommended action, three concrete actions for next month; Performance Score
            as XX out of 100; and Status as GREEN, YELLOW or RED.

            ## D. FINANCIAL CONTRIBUTION
            This table when the data allows it, and otherwise the required sentence from section 5
            followed by the list of numbers that would be needed:
            | HR | Active Drivers | Driver Contribution | HR Cost | Net Contribution | Target Contribution | Opportunity Gap | Financial Status |

            ## E. ACTIVITY VS RESULT ANALYSIS
            Place each HR into one of four categories: high activity and high results; high activity
            and low results; low activity and high results; low activity and low results. Explain
            what each case means operationally, paying particular attention to whether the calls,
            SMS and talk time are actually producing hires and active drivers.

            ## F. DRIVER RETENTION AND QUALITY
            Compare total hires with active drivers, and identify whose drivers are staying and
            whose are leaving. Calculate Active Retention as relevant active drivers divided by
            relevant hired drivers multiplied by 100, but only where the two populations cover the
            same period. Where the periods do not match, say so clearly instead of producing a
            misleading percentage.

            ## G. HR LEADER ANALYSIS
            Evaluate the HR Leader separately as an individual recruiter and as a team leader,
            including whether the other HRs' results indicate effective coaching and KPI control,
            and what the Leader should monitor during the next month.

            ## H. {MS} CONDITION
            Taking every factor into account: hiring capacity, active-driver base, HR productivity,
            communication activity, recruiting efficiency, driver retention, financial contribution,
            leadership, the major business risk, and growth potential. Results and active drivers
            carry more weight than raw activity. Do not judge the company's health on calls alone or
            on hires alone.

            ## I. MANAGEMENT ACTION PLAN
            The five most important management actions for the next 30 days, as a table:
            | Priority | Problem | Action | Responsible | KPI | Target | Expected effect |
            Priorities are P1 for immediate, P2 for important and P3 for improvement.

            ## J. FINAL MANAGEMENT CONCLUSION
            Answer directly: how well are the HRs meeting standards; which HR is the strongest;
            which needs the most improvement; is the HR Leader successfully managing the team; which
            metric is currently limiting the growth of {MS}; is {MS} productive on the available
            data; what will happen if the current trend continues for another three months; and what
            is the single most important action management should take next. Keep this section
            clear, decisive and useful to company management.

            # FORMAT

            The report is rendered into a PDF by a formatter that understands only these things:
            headings beginning "## " or "### ", pipe tables with a header row followed by a
            separator row, lines beginning "- " as bullets, bold marked with double asterisks, and
            ordinary paragraphs. Use nothing else. No numbered lists, no code fences, no block
            quotes, no nested bullets, no horizontal rules, no emoji. Keep table cells short: a cell
            longer than about 18 characters will be truncated.

            Where possible show both the actual result and the percentage against the standard.
            Write for management: analytical, direct and data-driven, not motivational.
            """;

    // -------------------------------------------------------------- the data

    /**
     * The figures, in the shape the brief says to expect them.
     *
     * <p>Every derived number the report leans on is computed here — working days, required monthly
     * totals, achievement percentages, position within the quarter — so that the model's job is
     * analysis rather than arithmetic. What is genuinely absent is named as absent.
     */
    private static String inputData(List<TeamReportRow> rows, LocalDate from, LocalDate to,
                                    String ms, ActivityStandard standard, String question) {
        int workingDays = workingDays(from, to);
        StringBuilder out = new StringBuilder(4096);

        out.append("Report Month: ").append(YearMonth.from(to))
                .append(" (").append(from).append(" to ").append(to).append(")\n")
                .append("MS: ").append(ms).append('\n')
                .append("Number of HRs: ").append(rows.size()).append('\n')
                .append("Working Days During Report Month: ").append(workingDays)
                .append(" (six-day week, Sundays excluded)\n\n");

        for (TeamReportRow row : rows) {
            appendPerson(out, row, workingDays, standard);
        }

        out.append("### COMPANY FINANCIAL DATA\n")
                .append("Average Monthly Net Profit Per Active Driver: not held in this system\n")
                .append("Other relevant company costs: not held in this system\n")
                .append("Salary, payroll cost, recruiting cost and other HR costs are not held in ")
                .append("this system and are unavailable for every HR listed above.\n\n");

        if (question != null && !question.isBlank()) {
            out.append("Additional Notes from the manager requesting this report: ")
                    .append(question.trim()).append('\n');
        }
        return out.toString();
    }

    private static void appendPerson(StringBuilder out, TeamReportRow row, int workingDays,
                                     ActivityStandard standard) {
        // Against working days, not against recorded shifts. The row's own target is per shift
        // stored, which is the right comparison for a dashboard and the wrong one for a monthly
        // report: somebody with four stored shifts in a twenty-six day month has not met the month
        // by working four good days, and a target that shrinks to match the gap would say they had.
        long callsRequired = (long) standard.getCallsPerDay() * workingDays;
        long smsRequired = (long) standard.getSmsSentPerDay() * workingDays;
        long talkRequired = Math.round(
                (double) standard.getTalkSecondsPerDay() * workingDays / 60.0);
        long talkMinutes = Math.round(row.getTalkSeconds() / 60.0);

        out.append("### ").append(row.getName()).append('\n')
                .append("Position: ")
                .append(row.getPosition().isBlank() ? "HR" : row.getPosition()).append('\n');

        // Activity always exists: it comes from this system's own database, not from a third party.
        out.append("Calls: ").append(row.getCalls()).append(" of ").append(callsRequired)
                .append(" required (").append(percent(row.getCalls(), callsRequired))
                .append("%)\n")
                .append("SMS: ").append(row.getSmsSent()).append(" of ").append(smsRequired)
                .append(" required (").append(percent(row.getSmsSent(), smsRequired))
                .append("%)\n")
                .append("Talk Time: ").append(talkMinutes).append(" minutes of ")
                .append(talkRequired).append(" required (")
                .append(percent(talkMinutes, talkRequired)).append("%)\n")
                .append("Shifts with recorded activity: ").append(row.getRecordedDays())
                .append(" of ").append(workingDays)
                .append(" working days, with all three daily targets met on ")
                .append(row.getDaysAllTargetsMet()).append(" of them\n");

        if (!row.getBoardMatched()) {
            out.append("Recruiting data: UNAVAILABLE. This person could not be found on the ")
                    .append("recruiting board, so their hires, active drivers, tenure, quarter and ")
                    .append("recruiting targets are all unknown. Do not treat any of it as zero. ")
                    .append("Do not include them in any hiring total, ranking or comparison, and ")
                    .append("do not score their recruiting KPIs. Score their activity only, and ")
                    .append("say plainly that the rest is unavailable.\n\n");
            return;
        }

        boolean inferred = "INFERRED".equals(row.getTenureSource());
        out.append("Employment Start, used as the start of quarter 1: ")
                .append(row.getTenureStartMonth().isBlank() ? "unknown" : row.getTenureStartMonth())
                .append(inferred
                        ? " (INFERRED from their first hire on the board, not a recorded start "
                          + "date. It can only be later than the truth, so their real tenure may "
                          + "be longer and their real quarter further on. Mention this once, under "
                          + "VALIDATION, and do not otherwise dwell on it.)"
                        : " (recorded)")
                .append('\n')
                .append("Months of tenure at the end of the reported month: ")
                .append(row.getTenureMonths()).append('\n');

        if (row.getRecruitingQuarter() >= 1) {
            int quarter = row.getRecruitingQuarter();
            out.append("Current Quarter: ").append(Math.min(quarter, 4));
            if (quarter > 4) {
                out.append(" standard, this being their quarter ").append(quarter)
                        .append(" and therefore past the first year, so the quarter 4 standard ")
                        .append("applies and continues to apply");
            }
            out.append('\n')
                    .append("Month within the quarter: ").append(row.getQuarterMonth())
                    .append(" of 3\n")
                    .append("Required Hires This Month: ").append(row.getRequiredHiresInMonth())
                    .append('\n')
                    .append("Required Hires For The Whole Quarter: ")
                    .append(row.getRequiredHiresInQuarter()).append('\n')
                    .append("Required Active Drivers By The End Of The Quarter: ")
                    .append(row.getRequiredActive()).append('\n')
                    .append("Drivers Hired This Month: ").append(row.getHiredInPeriod())
                    .append(" (")
                    .append(percent(row.getHiredInPeriod(), row.getRequiredHiresInMonth()))
                    .append("% of the monthly target)\n")
                    .append("Drivers Hired During The Current Quarter So Far: ")
                    .append(row.getHiredInQuarter()).append(" (")
                    .append(percent(row.getHiredInQuarter(), row.getRequiredHiresInQuarter()))
                    .append("% of the full quarterly target, where the expected progress by the ")
                    .append("end of month ").append(row.getQuarterMonth()).append(" is about ")
                    .append(Math.round(row.getQuarterMonth() * 100 / 3.0)).append("%)\n");
        } else {
            out.append("Current Quarter: could not be determined, because none of their drivers ")
                    .append("carries a hire date on the board. No recruiting target applies to ")
                    .append("them. Do not assume one.\n");
        }

        out.append("Current Active Drivers At The End Of The Reported Month: ")
                .append(row.getActiveAtMonthEnd());
        if (row.getRequiredActive() > 0) {
            out.append(" (").append(percent(row.getActiveAtMonthEnd(), row.getRequiredActive()))
                    .append("% of the required minimum)");
        }
        out.append('\n')
                .append("Of this month's hires, still driving today: ")
                .append(row.getStillActiveFromPeriod()).append(" of ")
                .append(row.getHiredInPeriod()).append('\n')
                .append("Drivers Terminated During The Reported Month: ")
                .append(row.getTerminatedInPeriod()).append('\n')
                .append("All time: ").append(row.getHiredToDate()).append(" hired, ")
                .append(row.getActiveNow()).append(" currently active, ")
                .append(row.getTerminatedToDate()).append(" terminated\n")
                .append("Salary, Payroll Cost, Recruiting Cost, Other Cost: not available\n\n");
    }

    /**
     * Working days in the reported range, on a six-day week.
     *
     * <p>Counted from the actual dates rather than assumed, because a month is 25, 26 or 27 working
     * days depending on where its Sundays fall, and a target built on the wrong one is wrong for
     * every person in the report at once.
     */
    private static int workingDays(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != REST_DAY) {
                days++;
            }
        }
        return days;
    }


    /** The MS this report covers. One, always — the gateway scopes the rows before they arrive. */
    private static String companyOf(List<TeamReportRow> rows) {
        Entity entity = rows.getFirst().getEntity();
        for (TeamReportRow row : rows) {
            if (row.getEntity() != entity) {
                return "the company";
            }
        }
        return entity == Entity.ENTITY_UNSPECIFIED
                ? "the company"
                : entity.name().replace("ENTITY_", "");
    }

    private static long percent(long actual, long target) {
        return target <= 0 ? 0 : Math.round(actual * 100.0 / target);
    }

    /** What the model actually objected to, in words an operator can act on. */
    private static String reasonFor(org.springframework.web.client.HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        int status = e.getStatusCode().value();
        if (body.contains("insufficient_quota") || body.contains("credit_balance_exhausted")) {
            return "The AI account has no credits left. Top it up, or set a different "
                   + "OPENAI_API_KEY.";
        }
        if (status == 401 || status == 403) {
            return "The AI key was rejected. Check OPENAI_API_KEY.";
        }
        if (status == 429) {
            return "The AI service is rate limiting this key. Try again shortly.";
        }
        return "The AI service refused the request (HTTP " + status + ").";
    }

    @SuppressWarnings("unchecked")
    private static String firstMessage(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object choices = body.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object message = ((Map<String, Object>) list.getFirst()).get("message");
        return message instanceof Map<?, ?> m ? (String) m.get("content") : null;
    }

    /**
     * A model call is slow by nature, and the default request factory would give up long before one
     * finishes. A ten-section report takes considerably longer than the paragraph this used to
     * produce, so the read timeout is the one that matters and it is generous.
     */
    private static ClientHttpRequestFactory timeouts(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return factory;
    }
}
