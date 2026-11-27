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
    void 설계도가_넣은_근거_문장은_답_노출_입력에_의도한_근거로_실린다() {
        var bp = TaskPipelineServiceTest.bizBlueprint();
        String out = TaskReviewService.intendedFacts(bp);
        assertThat(out).startsWith("[자료에 의도적으로 넣은 근거 문장");
        bp.facts().forEach(f -> assertThat(out).contains(f.text().strip()));
        assertThat(TaskReviewService.intendedFacts(null)).isEmpty();
    }
}
