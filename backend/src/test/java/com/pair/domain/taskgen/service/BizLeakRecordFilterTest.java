package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import org.junit.jupiter.api.Test;

/** BIZ 답 노출: 자료 속 회사 정의 기록 문장은 위반에서 뺀다(g9 8/10 실패 원인) */
class BizLeakRecordFilterTest {

    private static Violation v(String quote, String location) {
        return new Violation("M5", quote, location, "[결함: x] 고치세요");
    }

    @Test
    void 자료_속_기록_말투_정의는_위반이_아니다() {
        assertThat(TaskReviewService.recordedDefinition(v("인사팀의 교육비 지급대장에는 직원별 원 단위 확정액이 부서 지원금의 집계 대상으로 기록되어 있다.", "자료/교육비 정산 지침.pdf 2쪽"))).isTrue();
        assertThat(TaskReviewService.recordedDefinition(v("체결본의 수납 인정 기간 마지막 날은 실적 마감일 전날로 기재되어 있다.", "자료/협력사 계약 검토철.pdf 2쪽"))).isTrue();
        assertThat(TaskReviewService.recordedDefinition(v("출처 칸에는 '상담원 첫 답변 발송 기록'이 적혀 있었고 검토자의 서명이 남아 있었다.", "문서 자료/고객지원 보고 기록.pdf 1쪽"))).isTrue();
    }

    @Test
    void 계산_절차나_지시가_있으면_위반으로_둔다() {
        assertThat(TaskReviewService.recordedDefinition(v("월 값은 누계에서 전월 누계를 빼서 구한다고 기록되어 있다.", "자료/보고서.pdf 1쪽"))).isFalse();
        assertThat(TaskReviewService.recordedDefinition(v("수수료를 빼고 비교해야 한다고 적혀 있다.", "자료/보고서.pdf 1쪽"))).isFalse();
    }

    @Test
    void 설명_요구사항_문항_위치는_그대로_둔다() {
        assertThat(TaskReviewService.recordedDefinition(v("집계 대상으로 기록되어 있다.", "설명"))).isFalse();
        assertThat(TaskReviewService.recordedDefinition(v("집계 대상으로 기록되어 있다.", "문항 3 보기1"))).isFalse();
        assertThat(TaskReviewService.recordedDefinition(v("집계 대상은 확정액이다.", "자료/지침.pdf 2쪽"))).isFalse();
    }

    @Test
    void 객관식_보기_위치는_답_노출에서_뺀다() {
        assertThat(TaskReviewService.onChoice(v("30백만원 부족", "문항 3 보기4"))).isTrue();
        assertThat(TaskReviewService.onChoice(v("30백만원 부족", "choice:3:4"))).isTrue();
        assertThat(TaskReviewService.onChoice(v("부족액을 구하시오", "문항 3"))).isFalse();
    }

    @Test
    void 위반이_아니라고_적은_항목은_뺀다() {
        assertThat(TaskReviewService.selfDenied(new Violation("M2", "q", "question:3", "기준과 계산 값으로 답이 하나로 정해져 판단 지점이 아니므로 위반이 아닙니다. [단계:ANSWER]"))).isTrue();
        assertThat(TaskReviewService.selfDenied(new Violation("M2", "q", "question:3", "단위가 천 원인데 인정 답이 원 단위입니다."))).isFalse();
    }

    @Test
    void 설계도가_넣은_근거_문장은_답_노출_입력에_의도한_근거로_실린다() {
        var bp = TaskPipelineServiceTest.bizBlueprint();
        String out = TaskReviewService.intendedFacts(bp);
        assertThat(out).startsWith("[자료에 의도적으로 넣은 근거 문장");
        bp.facts().forEach(f -> assertThat(out).contains(f.text().strip()));
        assertThat(TaskReviewService.intendedFacts(null)).isEmpty();
    }

    @Test
    void BIZ_점검_입력에는_관련_적은_자료가_빠진다() {
        var bp = TaskPipelineServiceTest.bizBlueprint().withMaterials(java.util.List.of("자료/검토 안내.pdf"),
                java.util.List.of(new com.pair.domain.taskgen.dto.BlueprintDto.Filler("자료/지난 회의록.pdf", "지난 분기 회의", true)));
        var draft = TaskAssemblyService.biz(new com.pair.domain.taskgen.dto.TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20), bp,
                TaskPipelineServiceTest.bizWriting(""), java.util.List.of(), TaskPipelineServiceTest.bizAnswer(),
                new com.pair.domain.taskgen.dto.FillerDto(java.util.List.of(new com.pair.domain.taskgen.dto.DevDraftDto.FileDraft("자료/지난 회의록.pdf", "회의에서 일정을 정했다. 관련 적은 고유문장"))));
        assertThat(draft.documents()).extracting("path").contains("자료/지난 회의록.pdf");
        var needed = FillerService.withoutFillers(draft, bp);
        assertThat(needed.documents()).extracting("path").doesNotContain("자료/지난 회의록.pdf");
        assertThat(needed.questions()).isEqualTo(draft.questions());
        String leakInput = TaskReviewService.bizInput(TaskReviewService.Check.LEAK, new com.pair.domain.taskgen.dto.TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20), bp, draft);
        assertThat(leakInput).doesNotContain("관련 적은 고유문장");
    }
}
