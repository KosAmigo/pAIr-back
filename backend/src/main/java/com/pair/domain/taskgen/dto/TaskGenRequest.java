package com.pair.domain.taskgen.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.pair.domain.scoring.service.RubricService;
import com.pair.domain.task.entity.Task;

/**
 * 관리자가 내는 과제 생성 폼 (설계 4장).
 * usage 는 SELF(공개 문제, 기본)/LIVE(비공개 문제), minutes 는 20/40/60(기본 60).
 * 직무와 난이도는 받지 않는다. 난이도는 생성 모델이 과제를 보고 정한다.
 * competencies 는 이 과제가 특히 보는 역량 key 0~2개(선택). 추천 카드를 골라 만들면 그 카드의 값이 온다.
 * 비어 있으면 생성 모델이 정한다.
 */
public record TaskGenRequest(String track, String topic, String trap, String usage, Integer minutes,
        List<String> competencies) {

    public static final Set<String> COMPETENCY_KEYS = RubricService.ITEMS.stream().map(RubricService.Item::key)
            .collect(Collectors.toUnmodifiableSet());
    public static final String COMPETENCY_HELP = RubricService.ITEMS.stream().map(i -> i.key() + "(" + i.name() + ")")
            .collect(Collectors.joining(", "));
    public static final int MAX_COMPETENCIES = 2;

    /** 생성 프롬프트에 덧붙일 역량 안내 */
    public static final String COMPETENCY_PROMPT = "\n- competencies 는 이 과제가 특히 보는 역량 key 1~2개입니다. 가능한 key: " + COMPETENCY_HELP;

    /** 역량 없이 만든다(테스트·옛 코드용) */
    public TaskGenRequest(String track, String topic, String trap, String usage, Integer minutes) {
        this(track, topic, trap, usage, minutes, List.of());
    }

    /** 제한 시간(분) → 난이도. 난이도 이름이나 대응을 바꿀 때 고치는 유일한 곳(DB 값과 프런트 표시도 함께) */
    public static String difficultyOf(int minutes) {
        return switch (minutes) {
            case 20 -> "쉬움";
            case 40 -> "보통";
            default -> "어려움";
        };
    }
    public static final Set<Integer> MINUTES = Set.of(20, 40, 60);

    /** 직군 값 검사: 대문자 DEV 또는 BIZ. 아니면 IllegalArgumentException(400) */
    public static String cleanTrack(String track) {
        String t = clean(track).toUpperCase();
        if (t.isEmpty()) throw new IllegalArgumentException("직군 구분(track)을 입력하세요.");
        if (!t.equals("DEV") && !t.equals("BIZ")) throw new IllegalArgumentException("track 은 DEV 또는 BIZ 여야 합니다: " + track);
        return t;
    }

    /** 빈 값·범위를 검사하고 기본값을 채운 새 폼을 돌려준다. 잘못되면 IllegalArgumentException(400) */
    public TaskGenRequest validated() {
        String t = clean(track).toUpperCase();
        if (t.isEmpty()) throw new IllegalArgumentException("직군 구분(track)을 입력하세요.");
        if (!t.equals("DEV") && !t.equals("BIZ")) throw new IllegalArgumentException("track 은 DEV 또는 BIZ 여야 합니다: " + track);
        String tp = required(topic, "주제(topic)", 200);
        String tr = required(trap, "함정(trap)", 500);
        String u = clean(usage).isEmpty() ? "SELF" : clean(usage).toUpperCase();
        if (!u.equals("SELF") && !u.equals("LIVE")) throw new IllegalArgumentException("usage 는 SELF 또는 LIVE 여야 합니다.");
        int m = minutes == null ? 60 : minutes;
        if (!MINUTES.contains(m)) throw new IllegalArgumentException("제한 시간(minutes)은 20, 40, 60 중 하나여야 합니다.");
        return new TaskGenRequest(t, tp, tr, u, m, checkedCompetencies(competencies));
    }

    /** 역량을 바꾼 새 폼 */
    public TaskGenRequest withCompetencies(List<String> keys) {
        return new TaskGenRequest(track, topic, trap, usage, minutes, keys);
    }

    /** 요청의 역량 검사: 6개 key 만, 중복 없이 최대 2개. 아니면 IllegalArgumentException(400) */
    static List<String> checkedCompetencies(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String k : raw) {
            String v = clean(k).toLowerCase();
            if (!COMPETENCY_KEYS.contains(v)) throw new IllegalArgumentException("모르는 역량(competencies)입니다: " + k);
            if (!out.contains(v)) out.add(v);
        }
        if (out.size() > MAX_COMPETENCIES) throw new IllegalArgumentException("역량(competencies)은 " + MAX_COMPETENCIES + "개까지 고를 수 있습니다.");
        return out;
    }

    /** 생성 모델이 정한 역량: 잘못된 key 는 버리고 중복을 없애 2개까지. 없으면 빈 목록 */
    public static List<String> pickCompetencies(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String k : raw) {
            String v = clean(k).toLowerCase();
            if (COMPETENCY_KEYS.contains(v) && !out.contains(v) && out.size() < MAX_COMPETENCIES) out.add(v);
        }
        return out;
    }

    public Task.Usage usageValue() {
        return Task.Usage.valueOf(usage);
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    private static String required(String s, String label, int max) {
        String v = clean(s);
        if (v.isEmpty()) throw new IllegalArgumentException(label + "을(를) 입력하세요.");
        if (v.length() > max) throw new IllegalArgumentException(label + "은(는) " + max + "자 이하여야 합니다.");
        return v;
    }
}
