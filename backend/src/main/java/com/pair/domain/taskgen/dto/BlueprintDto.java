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
        List<String> questionPlan,
        /** 숨은 테스트(DEV)·문항(BIZ)이 확인할 사례 목록. 답 쪽 단계는 이 사례만 구현한다 */
        List<Case> cases,
        /** E6·M6: 풀이에 꼭 필요한 자료 묶음의 파일 이름(DEV: 시작 코드·공개 테스트·NOTES.md 경로, BIZ: 문서·CSV 경로). 20~30% */
        List<String> neededFiles,
        /** E6·M6: 관련 적은 자료 목록(파일 이름과 한 줄 내용). 단계 4'(싼 모델)가 결함·답을 모른 채 만든다 */
        List<Filler> fillers,
        /** DEV(Q7): 함수별 예시 표 위에 한 번 적는 미리 넣어 둔 데이터. 한 함수의 예시는 같은 준비 데이터를 쓴다 */
        List<ExampleSetup> exampleSetups,
        /** DEV(Q7): 모든 예시 값에 공통인 한 줄(시간대 등). 없으면 빈 값 */
        String exampleNote) {

    /** 예시 표 준비 데이터·공통 줄 없이 만든다(BIZ·테스트·옛 코드용) */
    public BlueprintDto(String title, String summary, String code, String category, List<String> competencies, String scenario, List<String> api,
            List<Fact> facts, List<Example> examples, List<Flaw> flaws, List<Judgment> judgments, List<String> trapKeywords, Conclusion conclusion,
            List<Table> tables, List<Figure> figures, List<Doc> docs, List<String> questionPlan, List<Case> cases, List<String> neededFiles, List<Filler> fillers) {
        this(title, summary, code, category, competencies, scenario, api, facts, examples, flaws, judgments, trapKeywords, conclusion, tables, figures, docs,
                questionPlan, cases, neededFiles, fillers, List.of(), "");
    }

    /** 필요한 묶음·관련 적은 자료 없이 만든다(테스트·옛 코드용) */
    public BlueprintDto(String title, String summary, String code, String category, List<String> competencies, String scenario, List<String> api,
            List<Fact> facts, List<Example> examples, List<Flaw> flaws, List<Judgment> judgments, List<String> trapKeywords, Conclusion conclusion,
            List<Table> tables, List<Figure> figures, List<Doc> docs, List<String> questionPlan, List<Case> cases) {
        this(title, summary, code, category, competencies, scenario, api, facts, examples, flaws, judgments, trapKeywords, conclusion, tables, figures, docs,
                questionPlan, cases, List.of(), List.of());
    }

    /** 필요한 묶음과 관련 적은 자료 목록만 바꾼 복사본 */
    public BlueprintDto withMaterials(List<String> neededFiles, List<Filler> fillers) {
        return new BlueprintDto(title, summary, code, category, competencies, scenario, api, facts, examples, flaws, judgments, trapKeywords, conclusion,
                tables, figures, docs, questionPlan, cases, neededFiles, fillers, exampleSetups, exampleNote);
    }

    /** 관련 적은 자료 하나: path 는 파일 이름(DEV src/…java, BIZ 자료/…pdf·csv), summary 는 한 줄 내용(결함·답과 상관없는 일반 업무 내용) */
    public record Filler(String path, String summary, Boolean confusing) {

        /** 헷갈리는 자료 표시 없이 만든다(테스트·옛 코드용) */
        public Filler(String path, String summary) {
            this(path, summary, false);
        }

        /** 헷갈리는 자료인가: 과제와 같은 영역의 이름·내용이지만 답·결함 근거·판단 근거는 없는 자료(M6) */
        public boolean confusingFlag() {
            return Boolean.TRUE.equals(confusing);
        }
    }

    /** 사례 없이 만든다(테스트·옛 코드용) */
    public BlueprintDto(String title, String summary, String code, String category, List<String> competencies, String scenario, List<String> api,
            List<Fact> facts, List<Example> examples, List<Flaw> flaws, List<Judgment> judgments, List<String> trapKeywords, Conclusion conclusion,
            List<Table> tables, List<Figure> figures, List<Doc> docs, List<String> questionPlan) {
        this(title, summary, code, category, competencies, scenario, api, facts, examples, flaws, judgments, trapKeywords, conclusion, tables, figures, docs,
                questionPlan, List.of());
    }

    /** 응시자 글에 그대로 들어갈 사실 한 문장. place 는 DEV: description 또는 notes, BIZ: 문서 경로 또는 description */
    public record Fact(String text, String place) {}

    /**
     * 입력 → 기대 출력 예. input 은 정확한 Java 호출(준비 호출 포함, 숨은 테스트·사례 근거용, 응시자에게는 안 보임).
     * 응시자는 method·args·expected 로 만든 표(매개변수 | 결과)를 본다(Q7, 2026-11-28). args 는 [매개변수 이름, 짧은 표시값] 쌍, 시그니처 순서
     */
    public record Example(String input, String expected, String method, List<List<String>> args) {

        /** 표 칸 없이 만든다(옛 설계도·테스트용) */
        public Example(String input, String expected) {
            this(input, expected, null, null);
        }
    }

    /** 한 함수의 예시들이 함께 쓰는 미리 넣어 둔 데이터 한 줄(method 는 "Class.method") */
    public record ExampleSetup(String method, String text) {}

    /** 숨은 결함(DEV 는 wrongAnswer: 이 결함을 놓친 흔한 오답 방식, separatedBy: 그 오답을 정답과 가르는 사례 name). evidence 는 응시자가 대조하면 결함이 보이는 사실 문장(facts 에 있는 그대로). caughtBy: DEV 숨은 테스트 메서드 이름, BIZ "Q2" 또는 "결론" */
    public record Flaw(String what, String where, String evidence, String caughtBy, String wrongAnswer, String separatedBy) {

        /** 오답 정보 없이 만든다(BIZ·테스트용) */
        public Flaw(String what, String where, String evidence, String caughtBy) {
            this(what, where, evidence, caughtBy, null, null);
        }
    }

    /** 판단 지점. basis 와 선택지별 fact 는 facts 에 있는 문장 그대로 */
    public record Judgment(String question, String basis, List<Option> options) {}

    public record Option(String text, String fact) {}

    public record Conclusion(String correct, String wrong) {}

    /** path: 자료/이름.csv, rows 의 숫자 칸은 쉼표 없는 숫자 */
    public record Table(String path, List<String> columns, List<List<String>> rows) {}

    /** partOf: 이 수가 일부인 전체 수의 label, sumOf: 이 수가 합계인 수들의 label */
    public record Figure(String label, String value, String partOf, List<String> sumOf) {}

    public record Doc(String path, String role) {}

    /**
     * 확인할 사례 하나. name 은 DEV 숨은 테스트 메서드 이름 또는 BIZ 문항 번호("Q2"), situation 은 입력·상황, expected 는 기대 결과,
     * basis 는 그 동작을 뒷받침하는 자료의 사실·예 문장(facts·examples 에 있는 그대로). flaws.caughtBy 는 사례 name 중 하나다.
     * BIZ 문항 정보: ask 는 구할 값(중립 문구), choices 는 객관식 보기(정답 expected 와 오답 값. 단답형은 빈 목록), judgment 는 판단 지점 문항(어느 보기도 인정)
     */
    public record Case(String name, String situation, String expected, String basis, String ask, List<String> choices, boolean judgment) {

        /** 문항 정보 없이 만든다(DEV·테스트용) */
        public Case(String name, String situation, String expected, String basis) {
            this(name, situation, expected, basis, "", List.of(), false);
        }
    }
}
