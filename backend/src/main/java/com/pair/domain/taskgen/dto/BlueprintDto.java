package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * 1단계 설계도: 글 없이 구조만. 숨은 결함, 판단 지점, 사실 목록, 예 목록, (BIZ) 숫자표를 정한다.
 * 작성 단계는 이 중 facts·examples 등 응시자에게 보여도 되는 것만 받는다(결함 정보는 받지 않는다).
 * DEV 는 api·examples, BIZ 는 tables·figures·docs·conclusion·questionPlan 을 쓰고 쓰지 않는 쪽은 빈 값.
 */
public record BlueprintDto(
        String title,
        String summary,
        String code,
        String category,
        List<String> competencies,
        /** 업무 상황 한두 문장(결함·원인 언급 없이) */
        String scenario,
        /** DEV: 응시자 글과 코드가 함께 쓰는 공개 클래스·메서드 시그니처 */
        List<String> api,
        List<Fact> facts,
        List<Example> examples,
        List<Flaw> flaws,
        List<Judgment> judgments,
        List<String> trapKeywords,
        /** BIZ: 정답 결론과 함정에 빠졌을 때의 결론 */
        Conclusion conclusion,
        /** BIZ 숫자표 ① CSV 가 되는 표. 코드가 CSV 로 만든다 */
        List<Table> tables,
        /** BIZ 숫자표 ② 문서에 쓰이는 이름 붙은 수. 문서의 숫자는 tables·figures 값만 쓴다 */
        List<Figure> figures,
        /** BIZ: 문서 계획(중립 이름) */
        List<Doc> docs,
        /** BIZ: 문항 계획("Q1 객관식 결론", "Q2 단답형 …"). flaws.caughtBy 의 Q 번호와 맞춘다 */
        List<String> questionPlan) {

    /** 응시자 글에 그대로 들어갈 사실 한 문장. place 는 DEV: description 또는 notes, BIZ: 문서 경로 또는 description */
    public record Fact(String text, String place) {}

    /** 입력 → 기대 출력 예(응시자에게 보임) */
    public record Example(String input, String expected) {}

    /** 숨은 결함. evidence 는 응시자가 대조하면 결함이 보이는 사실 문장(facts 에 있는 그대로). caughtBy: DEV 숨은 테스트 메서드 이름, BIZ "Q2" 또는 "결론" */
    public record Flaw(String what, String where, String evidence, String caughtBy) {}

    /** 판단 지점. basis 와 선택지별 fact 는 facts 에 있는 문장 그대로 */
    public record Judgment(String question, String basis, List<Option> options) {}

    public record Option(String text, String fact) {}

    public record Conclusion(String correct, String wrong) {}

    /** path: 자료/이름.csv, rows 의 숫자 칸은 쉼표 없는 숫자 */
    public record Table(String path, List<String> columns, List<List<String>> rows) {}

    /** partOf: 이 수가 일부인 전체 수의 label, sumOf: 이 수가 합계인 수들의 label */
    public record Figure(String label, String value, String partOf, List<String> sumOf) {}

    public record Doc(String path, String role) {}
}
