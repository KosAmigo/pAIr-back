package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 단독 풀이를 과제의 AI 제한 안에서 돌리는 도구(E6·M6, 기준 문서 3장 난이도 목표).
 * 고르지 않는 단순한 방식이다: 자료를 앞에서부터 순서대로 붙이되, 한 번에 붙일 수 있는 파일 수와
 * 첨부 맥락 글자 합계를 넘으면 거기서 멈춘다(넘는 파일은 한도까지만 자르고, 그 뒤 파일은 붙이지 않는다). 모델을 부르지 않는다.
 */
final class SoloLimitService {

    private SoloLimitService() {}

    /** 자료 하나(경로, 글) */
    record Material(String path, String text) {}

    /** 붙인 자료(글을 자른 것 포함)와 붙이지 못한 파일 경로 */
    record Attached(List<Material> attached, List<String> omitted, int chars) {}

    /** limits 가 null 이면 모두 붙인다(옛 동작) */
    static Attached attach(List<Material> ordered, AiLimitsDto limits) {
        List<Material> out = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int chars = 0;
        for (Material m : ordered) {
            if (m == null || m.text() == null) continue;
            if (limits == null) {
                out.add(m);
                chars += m.text().length();
                continue;
            }
            int room = limits.contextChars() - chars;
            if (out.size() >= limits.maxAttach() || room <= 0) {
                omitted.add(m.path());
                continue;
            }
            String t = m.text().length() > room ? m.text().substring(0, room) : m.text();
            out.add(new Material(m.path(), t));
            chars += t.length();
        }
        return new Attached(out, omitted, chars);
    }

    /** 검사 설명에 붙일 한 줄: 무엇을 붙이고 무엇을 못 붙였는지 */
    static String note(Attached a, AiLimitsDto limits) {
        if (limits == null) return "";
        return "제한(턴 " + limits.turns() + "·첨부 " + limits.maxAttach() + "개·" + limits.contextChars() + "자) 안에서 앞에서부터 붙임: "
                + a.attached().stream().map(Material::path).toList() + (a.omitted().isEmpty() ? "" : ", 못 붙인 파일 " + a.omitted()) + ". ";
    }
}
