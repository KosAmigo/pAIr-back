package com.pair.taskgen;

import java.util.Set;

import com.pair.task.TaskRecord;

/**
 * 관리자가 내는 과제 생성 폼 (설계 4장).
 * usage 는 SELF(공개 문제, 기본)/LIVE(비공개 문제), minutes 는 40/60(기본 60).
 */
public record TaskGenForm(String track, String jobRole, String difficulty, String topic, String trap, String usage,
        Integer minutes) {

    public static final Set<String> DIFFICULTIES = Set.of("초급", "중급", "고급");

    /** 빈 값·범위를 검사하고 기본값을 채운 새 폼을 돌려준다. 잘못되면 IllegalArgumentException(400) */
    public TaskGenForm validated() {
        String t = clean(track).toUpperCase();
        if (t.isEmpty()) throw new IllegalArgumentException("직군 구분(track)을 입력하세요.");
        if (t.equals("BIZ")) throw new IllegalArgumentException("비개발(BIZ) 과제 생성은 준비 중입니다.");
        if (!t.equals("DEV")) throw new IllegalArgumentException("track 은 DEV 만 쓸 수 있습니다: " + track);
        String role = required(jobRole, "직군(jobRole)", 50);
        String diff = clean(difficulty);
        if (!DIFFICULTIES.contains(diff)) throw new IllegalArgumentException("난이도는 초급, 중급, 고급 중 하나여야 합니다.");
        String tp = required(topic, "주제(topic)", 200);
        String tr = required(trap, "함정(trap)", 500);
        String u = clean(usage).isEmpty() ? "SELF" : clean(usage).toUpperCase();
        if (!u.equals("SELF") && !u.equals("LIVE")) throw new IllegalArgumentException("usage 는 SELF 또는 LIVE 여야 합니다.");
        int m = minutes == null ? 60 : minutes;
        if (m != 40 && m != 60) throw new IllegalArgumentException("제한 시간(minutes)은 40 또는 60 이어야 합니다.");
        return new TaskGenForm(t, role, diff, tp, tr, u, m);
    }

    public TaskRecord.Usage usageValue() {
        return TaskRecord.Usage.valueOf(usage);
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
