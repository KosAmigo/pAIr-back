package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 다시 돌릴 단계를 검사가 결과에 직접 담는지(detail 글을 다시 읽지 않는다) */
class StageOwnersFromChecksTest {

    @Test
    @DisplayName("DEV 형식 검사: 정답 쪽 항목 → ANSWER, 설명·요구사항·후속 → WRITING, 시작 코드·테스트·코드 경로 → CODE, 그 밖의 경로 → ANSWER, 그 밖 → 빈 목록(설계도부터)")
    void devFormatStages() {
        var draft = TaskGenFixtures.good();
        String path = draft.files().get(0).path();
        assertThat(DevCheckService.formatFail("정답 설명(answer)이 비어 있습니다.", draft).stages()).containsExactly("ANSWER");
        assertThat(DevCheckService.formatFail("설명(description)이 비어 있습니다.", draft).stages()).containsExactly("WRITING");
        assertThat(DevCheckService.formatFail("공개 테스트(test/…)가 없습니다.", draft).stages()).containsExactly("CODE");
        assertThat(DevCheckService.formatFail("파일 경로 형식이 맞지 않습니다: " + path, draft).stages()).containsExactly("CODE");
        assertThat(DevCheckService.formatFail("정체 모를 경로 문제", draft).stages()).containsExactly("ANSWER");
        var unknown = DevCheckService.formatFail("제목이 비어 있습니다.", draft);
        assertThat(unknown.stages()).isEmpty();
        assertThat(DevPipelineService.owners(unknown, List.of())).isEmpty();
        assertThat(DevPipelineService.owners(DevCheckService.formatFail("설명(description)이 비어 있습니다.", draft).withStages("CODE"), List.of())).containsExactly(Stage.CODE);
    }

    @Test
    @DisplayName("BIZ 형식 검사: 문서·설명 → WRITING, 객관식·보기·문항 → QUESTIONS+ANSWER, 체크리스트·단답형 → ANSWER, 그 밖 → 빈 목록")
    void bizFormatStages() {
        assertThat(BizCheckService.formatFail("문서(PDF)가 2개 이상 있어야 합니다.").stages()).containsExactly("WRITING");
        assertThat(BizCheckService.formatFail("객관식은 보기가 4개여야 합니다: x").stages()).containsExactly("QUESTIONS", "ANSWER");
        assertThat(BizCheckService.formatFail("단답형에 계산식(calc)이 없습니다").stages()).containsExactly("ANSWER");
        assertThat(BizCheckService.formatFail("제목이 비어 있습니다.").stages()).isEmpty();
        assertThat(BizPipelineService.owners(BizCheckService.formatFail("객관식은 보기가 4개여야 합니다: x"))).containsExactlyInAnyOrder(Stage.QUESTIONS, Stage.ANSWER);
    }

    @Test
    @DisplayName("한자 검사: 한자가 나온 글의 표시에 맞는 단계를 모두 담는다")
    void hanStages() {
        var r = TaskWritingService.hanCheck(new java.util.LinkedHashMap<>(java.util.Map.of("[코드]", "結論")) {{ put("[관련 적은 자료]", "結論"); put("[설계도]", "정상"); }});
        assertThat(r.stages()).containsExactlyInAnyOrder("CODE", "FILLER");
        assertThat(DevPipelineService.owners(r, List.of())).containsExactlyInAnyOrder(Stage.CODE, Stage.FILLER);
    }

    @Test
    @DisplayName("담긴 단계가 없으면 검사 이름 표로, 관문은 담긴 단계보다 앞선다")
    void gateAndNameTableFirst() {
        var named = new GenCheckResultDto(TaskPipelineService.FACTS_CHECK, false, "x");
        assertThat(DevPipelineService.owners(named, List.of())).containsExactly(Stage.WRITING);
        var gate = new GenCheckResultDto(TaskPipelineService.KEY_GATE, false, "M2 [Q1] 정답 키").withStages("CODE");
        assertThat(BizPipelineService.owners(gate)).containsExactly(Stage.ANSWER);
    }
}
