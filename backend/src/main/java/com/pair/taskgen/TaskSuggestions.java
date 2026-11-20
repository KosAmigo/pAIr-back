package com.pair.taskgen;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.ai.AiUnavailableException;
import com.pair.task.TaskRecord;
import com.pair.task.TaskRecordRepository;

/**
 * 과제 폼의 주제·함정을 AI(싼 모델)가 후보 3개로 추천한다.
 * 이미 있는 과제·앞서 받은 주제와 겹치지 않게 하고, 3개가 안 되거나 유형이 겹치면 1번만 다시 요청한다.
 */
@Service
public class TaskSuggestions {

    private static final Logger log = LoggerFactory.getLogger(TaskSuggestions.class);
    static final int COUNT = 3;
    static final int MAX_EXCLUDE = 20;
    static final int PER_MINUTE = 10;

    public record Request(String track, String jobRole, String difficulty, List<String> exclude) {}

    public record Suggestion(String jobRole, String difficulty, String topic, String trap, String trapType, String why) {}

    public record Result(List<Suggestion> suggestions) {}

    private static final String DEV_TYPES = "동시성, 경계값, 반올림·부동소수점, 캐시 무효화, 시간대·날짜 계산, 정렬 기준 누락, null·빈 값 처리, 정수 오버플로, 예외를 삼킴, 상태 전이 누락, 중복 처리(멱등성)";
    private static final String BIZ_TYPES = "단위 혼동, %와 %p, 상관과 인과, 기간 길이 차이, 표본 편향, 평균의 함정(가중평균), 기준 시점 불일치, 누락·중복 데이터, 세금·수수료 포함 여부, 누적과 증분 혼동";

    private static final String SYSTEM = """
            당신은 AI 활용 역량 평가 플랫폼의 과제 기획자입니다. 응시자가 AI 어시스턴트의 답을 그대로 믿지 않고 검증하는지 보는 과제의 주제와 함정 후보를 추천합니다.
            함정은 AI 가 틀리기 쉬운 지점이어야 하고, 응시자가 검증해서 잡아낼 수 있어야 합니다.
            규칙:
            - 후보는 정확히 3개이고, trapType(함정 유형)이 서로 모두 달라야 합니다.
            - 이미 있는 과제나 제외 목록과 주제가 겹치면 안 됩니다.
            - 실제 회사 이름은 쓰지 않고, 모든 글은 한국어로 씁니다.
            - topic 은 한 문장(50자 이내), trap 은 구체적으로 어떤 오류가 숨어 있는지 한두 문장(150자 이내), why 는 AI 가 왜 이걸 틀리기 쉬운지 한 줄(80자 이내)입니다.
            - trapType 은 짧은 유형 이름입니다.
            """;

    private static final String DEV_RULES = "[개발 과제 조건] 순수 Java 21 + JUnit 5 로 재현되는 버그만 추천합니다. 외부 라이브러리·DB·네트워크·Spring 이 필요한 주제는 안 됩니다. 파일 3~6개 규모로 만들 수 있어야 합니다.\n"
            + "함정 유형은 이 중에서 고릅니다: " + DEV_TYPES;

    private static final String BIZ_RULES = "[비개발 과제 조건] CSV 1~2개와 문서 2개로 검산할 수 있는 함정만 추천합니다. 정답이 문서 여러 개를 함께 봐야 나오는 주제여야 합니다.\n"
            + "함정 유형은 이 중에서 고릅니다: " + BIZ_TYPES;

    private final TaskGenAi ai;
    private final TaskGenProperties props;
    private final TaskRecordRepository records;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> calls = new HashMap<>();

    public TaskSuggestions(TaskGenAi ai, TaskGenProperties props, TaskRecordRepository records, Clock clock) {
        this.ai = ai;
        this.props = props;
        this.records = records;
        this.clock = clock;
    }

    /** 요청 검사. 잘못되면 IllegalArgumentException(400). track 은 대문자로 맞춘 새 요청을 돌려준다 */
    static Request validated(Request r) {
        if (r == null) throw new IllegalArgumentException("요청 내용이 없습니다.");
        String t = clean(r.track()).toUpperCase();
        if (!t.equals("DEV") && !t.equals("BIZ")) throw new IllegalArgumentException("track 은 DEV 또는 BIZ 여야 합니다.");
        String role = clean(r.jobRole());
        if (role.length() > 50) throw new IllegalArgumentException("직군(jobRole)은 50자 이하여야 합니다.");
        String diff = clean(r.difficulty());
        if (!diff.isEmpty() && !TaskGenForm.DIFFICULTIES.contains(diff)) throw new IllegalArgumentException("난이도는 초급, 중급, 고급 중 하나여야 합니다.");
        List<String> ex = new ArrayList<>();
        if (r.exclude() != null) {
            if (r.exclude().size() > MAX_EXCLUDE) throw new IllegalArgumentException("exclude 는 " + MAX_EXCLUDE + "개까지 보낼 수 있습니다.");
            for (String s : r.exclude()) {
                String v = clean(s);
                if (v.length() > 200) throw new IllegalArgumentException("exclude 항목은 200자 이하여야 합니다.");
                if (!v.isEmpty()) ex.add(v);
            }
        }
        return new Request(t, role, diff, ex);
    }

    @Transactional(readOnly = true)
    public Result suggest(Request raw, long adminId) {
        Request req = validated(raw);
        checkRate(adminId);

        String system = SYSTEM + (req.track().equals("DEV") ? DEV_RULES : BIZ_RULES);
        String user = user(req, records.findTop50ByOrderByIdDesc());

        List<Suggestion> first = call(system, user, req);
        if (first.size() == COUNT) return new Result(first);

        String retry = user + "\n\n앞서 받은 답은 조건(정확히 3개, 빈 칸 없음, 함정 유형이 모두 다름)을 지키지 않았습니다. 조건을 지켜 다시 3개를 추천해 주세요.";
        List<Suggestion> second;
        try {
            second = call(system, retry, req);
        } catch (AiUnavailableException e) {
            second = List.of();
        }
        List<Suggestion> merged = new ArrayList<>(second);
        Set<String> types = new HashSet<>();
        merged.forEach(s -> types.add(typeKey(s)));
        for (Suggestion s : first) if (types.add(typeKey(s))) merged.add(s);
        if (merged.isEmpty()) throw new AiUnavailableException("AI 가 쓸 만한 추천을 만들지 못했습니다. 잠시 후 다시 시도하세요.");
        return new Result(new ArrayList<>(merged.subList(0, Math.min(COUNT, merged.size()))));
    }

    /** 한 번 부르고, 빈 칸이 있거나 유형이 겹치는 것을 걸러 돌려준다 */
    private List<Suggestion> call(String system, String user, Request req) {
        TaskGenAi.Output<Result> out = ai.generate(props.suggestModel(), system, user, Result.class, props.suggestMaxTokens(), props.suggestEffort());
        log.info("과제 추천 호출: model={} 입력 토큰={} 출력 토큰={}", props.suggestModel(), out.inputTokens(), out.outputTokens());
        List<Suggestion> ok = new ArrayList<>();
        Set<String> types = new HashSet<>();
        if (out.value() == null || out.value().suggestions() == null) return ok;
        for (Suggestion s : out.value().suggestions()) {
            if (s == null) continue;
            Suggestion c = new Suggestion(
                    req.jobRole().isEmpty() ? clean(s.jobRole()) : req.jobRole(),
                    req.difficulty().isEmpty() ? clean(s.difficulty()) : req.difficulty(),
                    clean(s.topic()), clean(s.trap()), clean(s.trapType()), clean(s.why()));
            boolean blank = c.jobRole().isEmpty() || c.topic().isEmpty() || c.trap().isEmpty() || c.trapType().isEmpty() || c.why().isEmpty();
            if (blank || !TaskGenForm.DIFFICULTIES.contains(c.difficulty())) continue;
            if (!types.add(typeKey(c))) continue;
            ok.add(c);
        }
        return ok.size() > COUNT ? new ArrayList<>(ok.subList(0, COUNT)) : ok;
    }

    static String user(Request req, List<TaskRecord> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("직군 구분: ").append(req.track().equals("DEV") ? "개발(DEV)" : "비개발(BIZ)").append('\n');
        sb.append("직무: ").append(req.jobRole().isEmpty() ? "정해지지 않음 (직무도 다양하게 정해 주세요)" : req.jobRole()).append('\n');
        sb.append("난이도: ").append(req.difficulty().isEmpty() ? "정해지지 않음 (후보마다 알맞게 초급, 중급, 고급 중 하나로 정해 주세요)" : req.difficulty()).append('\n');
        if (!existing.isEmpty()) {
            sb.append("\n이미 있는 과제(이것들과 주제가 겹치지 않게):\n");
            for (TaskRecord t : existing) {
                sb.append("- ").append(t.getTitle());
                if (t.getSummary() != null && !t.getSummary().isBlank()) sb.append(": ").append(t.getSummary());
                sb.append('\n');
            }
        }
        if (!req.exclude().isEmpty()) {
            sb.append("\n방금 이미 추천한 주제(다시 추천하지 마세요):\n");
            for (String e : req.exclude()) sb.append("- ").append(e).append('\n');
        }
        sb.append("\n위 조건으로 후보 3개를 추천해 주세요.");
        return sb.toString();
    }

    /** 관리자 1명당 1분에 PER_MINUTE 번까지. 넘으면 TooManyJobsException(429) */
    private void checkRate(long adminId) {
        Instant now = clock.instant();
        synchronized (calls) {
            Deque<Instant> q = calls.computeIfAbsent(adminId, k -> new ArrayDeque<>());
            while (!q.isEmpty() && Duration.between(q.peekFirst(), now).compareTo(Duration.ofMinutes(1)) >= 0) q.pollFirst();
            if (q.size() >= PER_MINUTE) throw new TooManyJobsException("추천은 1분에 " + PER_MINUTE + "번까지 받을 수 있습니다. 잠시 후 다시 시도하세요.");
            q.addLast(now);
        }
    }

    private static String typeKey(Suggestion s) {
        return s.trapType().replaceAll("\\s+", "").toLowerCase();
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
