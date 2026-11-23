package com.pair.domain.taskgen.dto;

import java.util.Set;

import com.pair.domain.task.entity.Task;

/**
 * 관리자가 내는 과제 생성 폼 (설계 4장).
 * usage 는 SELF(공개 문제, 기본)/LIVE(비공개 문제), minutes 는 20/40/60(기본 60).
 * 직무와 난이도는 받지 않는다. 난이도는 생성 모델이 과제를 보고 정한다.
 */
public record TaskGenRequest(String track, String topic, String trap, String usage, Integer minutes) {

    public static final Set<String> DIFFICULTIES = Set.of("초급", "중급", "고급");
    public static final Set<Integer> MINUTES = Set.of(20, 40, 60);

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
        return new TaskGenRequest(t, tp, tr, u, m);
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
