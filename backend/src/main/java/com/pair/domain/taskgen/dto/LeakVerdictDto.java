package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * 답 노출 AI 판정(M5)과 자료 숫자 모순 판정(M1)의 출력(한 번의 호출). 없으면 빈 목록. JSON Schema(strict 구조화 출력)를 이 record 에서 만든다.
 * contradictions 는 null 이어도 된다(옛 호출·테스트 호환)
 */
public record LeakVerdictDto(List<Leak> leaks, List<Contradiction> contradictions) {

    public LeakVerdictDto(List<Leak> leaks) {
        this(leaks, List.of());
    }

    /** quote: 응시자에게 보이는 글에서 그대로 복사한 위반 문장, flaw: 관련된 숨은 결함, why: 왜 답을 알려 주는지 한 줄 */
    public record Leak(String quote, String flaw, String why) {}

    /** quote: 서로 모순되는 숫자가 든 문장·CSV 줄을 보이는 글에서 그대로 복사한 것, why: 어떤 모순인지 한 줄 */
    public record Contradiction(String quote, String why) {}
}
