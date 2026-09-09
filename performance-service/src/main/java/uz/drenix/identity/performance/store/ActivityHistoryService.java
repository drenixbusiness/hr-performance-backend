package uz.drenix.identity.performance.store;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.grpc.v1.AgentHistory;
import uz.drenix.identity.grpc.v1.DailyActivity;
import uz.drenix.identity.performance.ringcentral.ActivityService;

/**
 * Reading the stored shifts back.
 *
 * <p>The whole point of the table: a month costs one indexed query here, and minutes of throttled
 * paging at RingCentral. Nothing in this class talks to RingCentral at all.
 *
 * <p>Targets are applied <em>now</em>, against whatever the standard currently is. A verdict
 * stored alongside the counts would freeze last month against a bar that has since moved, and
 * anybody comparing two months would be comparing two different rulers.
 */
@Service
public class ActivityHistoryService {

    private final DailyActivityRepository store;

    public ActivityHistoryService(DailyActivityRepository store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<AgentHistory> read(List<String> userIds, LocalDate from, LocalDate to,
                                   ActivityService.Standard standard) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        // Kept in the order asked for. A response that silently reshuffles the list is a trap for
        // anybody zipping it against the one they sent.
        Map<UUID, String> wanted = new LinkedHashMap<>();
        for (String id : userIds) {
            try {
                wanted.put(UUID.fromString(id), id);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Not a user id: " + id);
            }
        }

        Map<UUID, List<DailyActivityEntity>> byUser = new HashMap<>();
        for (DailyActivityEntity row : store.range(wanted.keySet(), from, to)) {
            byUser.computeIfAbsent(row.getId().getUserId(), k -> new ArrayList<>()).add(row);
        }

        List<AgentHistory> history = new ArrayList<>(wanted.size());
        wanted.forEach((id, original) ->
                history.add(toProto(original, byUser.getOrDefault(id, List.of()), standard)));
        return history;
    }

    private static AgentHistory toProto(String agentId, List<DailyActivityEntity> days,
                                        ActivityService.Standard standard) {
        AgentHistory.Builder agent = AgentHistory.newBuilder().setAgentId(agentId);

        int calls = 0;
        long talk = 0;
        int sms = 0;
        int metAll = 0;

        for (DailyActivityEntity day : days) {
            boolean callsMet = day.getCalls() >= standard.callsPerDay();
            boolean talkMet = day.getTalkSeconds() >= standard.talkSecondsPerDay();
            boolean smsMet = day.getSmsSent() >= standard.smsSentPerDay();
            if (callsMet && talkMet && smsMet) {
                metAll++;
            }

            calls += day.getCalls();
            talk += day.getTalkSeconds();
            sms += day.getSmsSent();

            agent.addDays(DailyActivity.newBuilder()
                    .setDate(day.getId().getShiftDate().toString())
                    .setCalls(day.getCalls())
                    .setTalkSeconds(day.getTalkSeconds())
                    .setSmsSent(day.getSmsSent())
                    .setCallsMet(callsMet)
                    .setTalkMet(talkMet)
                    .setSmsMet(smsMet)
                    .build());
        }

        return agent
                .setTotalCalls(calls)
                .setTotalTalkSeconds(talk)
                .setTotalSmsSent(sms)
                .setRecordedDays(days.size())
                .setDaysAllTargetsMet(metAll)
                .build();
    }
}
