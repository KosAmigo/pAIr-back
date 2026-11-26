package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M4 판단 지점 근거 검사. v8 04·06 의 실제 문장으로 오탐이 사라졌는지 본다 */
class GenTextServiceTest {

    private static GenCheckResultDto check(DevDraftDto.JudgmentPoint p, String corpus) {
        return GenTextService.judgmentPoints(List.of(p), corpus, false);
    }

    // v8 04 검토 메모.pdf 2쪽 (문장부호·띄어쓰기는 일부러 모델 출력과 다르게 쓴다)
    private static final String CORPUS_04 = "운송사 확인은 외부 회신을 앞당기고 재무 확인은 증빙 누락을 미리 살피는 데 유리하다. 운송사 담당자는 평일 오후에 청구 관련 문의를 접수한다.";
    private static final String BASIS_04 = "운송사 확인은 외부 회신을 앞당기고 재무 확인은 증빙 누락을 미리 살피는 데 유리하다";

    @Test
    @DisplayName("M4 v8 04: 한 문장이 두 선택지의 근거면 통과한다 (문장부호·띄어쓰기 차이 허용)")
    void oneSentenceTwoOptions() {
        var p = new DevDraftDto.JudgmentPoint("무엇을 먼저?", List.of("운송사 확인을 먼저 요청한다.", "재무 확인을 먼저 요청한다."), BASIS_04,
                List.of("운송사 확인은 외부 회신을 앞당기고,  재무 확인은 증빙 누락을 미리 살피는 데 유리하다."));
                assertThat(check(p, CORPUS_04).ok()).isTrue();
    }

    @Test
    @DisplayName("M4 v8 04: evidence 가 '자료/검토 메모.pdf 2쪽' 위치뿐이면 fail 이 아니라 warn 이다")
    void locationOnlyWarns() {
        var p = new DevDraftDto.JudgmentPoint("무엇을 먼저?", List.of("운송사 확인을 먼저 요청한다.", "재무 확인을 먼저 요청한다."), BASIS_04, List.of("자료/검토 메모.pdf 2쪽"));
        var r = check(p, CORPUS_04);
        assertThat(r.needsRegeneration()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.detail()).contains("위치");
    }

    @Test
    @DisplayName("M4 v8 06: 위치만 적힌 evidence 두 줄도, 선택지 말이 없는 위치 한 줄도 warn 이다")
    void locationsV8_06() {
        String c = "퇴사자 면담 기록을 먼저 검토하면 이미 접수된 의견을 빠르게 살펴볼 수 있고, 관리자 배치 점검을 먼저 하면 현재 근무 환경을 살펴볼 수 있다. 경영회의는 제한된 시간에 인사 현황과 다른 안건도 함께 다룬다.";
        var p1 = new DevDraftDto.JudgmentPoint("첫 2주 활동?", List.of("퇴사자 면담 기록 검토를 먼저 진행", "관리자 배치 점검을 먼저 진행"),
                "퇴사자 면담 기록을 먼저 검토하면 이미 접수된 의견을 빠르게 살펴볼 수 있고, 관리자 배치 점검을 먼저 하면 현재 근무 환경을 살펴볼 수 있다.",
                List.of("자료/운영 검토.pdf 1쪽", "자료/운영 검토.pdf 3쪽"));
        var p2 = new DevDraftDto.JudgmentPoint("회의 자료?", List.of("숫자 요약을 먼저 준비", "면담 의견 요약을 먼저 준비"),
                "경영회의는 제한된 시간에 인사 현황과 다른 안건도 함께 다룬다.", List.of("자료/운영 검토.pdf 2쪽"));
        assertThat(check(p1, c).level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(check(p2, c).level()).isEqualTo(GenCheckResultDto.WARN);
    }

    @Test
    @DisplayName("M4: 문장인데 자료에 없으면 여전히 fail 이다")
    void fabricatedSentenceFails() {
        var p = new DevDraftDto.JudgmentPoint("무엇을 먼저?", List.of("운송사 확인을 먼저 요청한다.", "재무 확인을 먼저 요청한다."), BASIS_04,
                List.of("운송사 확인은 항상 사흘이 걸리고 재무 확인은 하루가 걸린다고 합니다."));
        assertThat(check(p, CORPUS_04).needsRegeneration()).isTrue();
    }
}
