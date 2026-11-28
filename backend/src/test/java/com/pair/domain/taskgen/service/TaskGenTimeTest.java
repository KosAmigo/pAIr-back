package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제한 시간(20/40/60)에 따른 폼 검사, 프롬프트 깊이 문구, d4 시점, 난이도 정리 */
class TaskGenTimeTest {

    private static TaskGenRequest form(String track, Integer minutes) {
        return new TaskGenRequest(track, "주제", "함정", "SELF", minutes);
    }

    @Test
    @DisplayName("minutes 는 20, 40, 60 만 허용하고 비우면 60, 그 밖은 400 용 예외")
    void minutesValidation() {
        for (int m : List.of(20, 40, 60)) assertThat(form("DEV", m).validated().minutes()).isEqualTo(m);
        assertThat(form("DEV", null).validated().minutes()).isEqualTo(60);
        assertThatThrownBy(() -> form("DEV", 30).validated()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20, 40, 60");
        assertThatThrownBy(() -> form("DEV", 90).validated()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("개발 과제 프롬프트에 시간별 깊이가 들어간다")
    void devDepth() {
        DevTaskGeneratorService g = new DevTaskGeneratorService(null, null, null, null, null);
        assertThat(g.system(form("DEV", 20))).contains("20분", "파일 2~3개", "기능 1개", "판단 지점 1개", "숨은 결함 1개", "숨은 테스트 1~2개").doesNotContain("difficulty");
        assertThat(g.system(form("DEV", 40))).contains("파일 3~5개", "2단계", "판단 지점 1~2개", "숨은 결함 1~2개");
        assertThat(g.system(form("DEV", 60))).contains("파일 4~6개", "3단계", "판단 지점 2개", "숨은 결함 2개");
        assertThat(g.user(form("DEV", 20), List.of())).contains("제한 시간: 20분", "주제: 주제", "함정: 함정").doesNotContain("직군", "난이도");
    }

    @Test
    @DisplayName("비개발 과제 프롬프트에 시간별 깊이가 들어간다")
    void bizDepth() {
        BizTaskGeneratorService g = new BizTaskGeneratorService(null, null, null, null, null, null);
        assertThat(g.system(form("BIZ", 20))).contains("CSV 1개 + 문서 2개", "각 1~2쪽", "문항 3개", "객관식 2개, 단답형 1개", "판단 지점 1개", "함정 1개");
        assertThat(g.system(form("BIZ", 40))).contains("CSV 1~2개 + 문서 2개", "문항 4개");
        assertThat(g.system(form("BIZ", 60))).contains("문서 2~3개", "판단 지점 2개", "함정 2개");
        assertThat(g.system(form("BIZ", 60))).doesNotContain("difficulty");
    }

    @Test
    @DisplayName("d4Minutes 는 60분 기준 [20,30,40,50] 에 시간 비율을 곱한 정수")
    void d4Proportional() {
        assertThat(GeneratedTaskRepository.d4Minutes(60)).containsExactly(20, 30, 40, 50);
        assertThat(GeneratedTaskRepository.d4Minutes(40)).containsExactly(13, 20, 26, 33);
        assertThat(GeneratedTaskRepository.d4Minutes(20)).containsExactly(6, 10, 13, 16);
    }

    @Test
    @DisplayName("난이도는 제한 시간으로 정한다: 20분=쉬움, 40분=보통, 60분=어려움")
    void difficultyByMinutes() {
        assertThat(TaskGenRequest.difficultyOf(20)).isEqualTo("쉬움");
        assertThat(TaskGenRequest.difficultyOf(40)).isEqualTo("보통");
        assertThat(TaskGenRequest.difficultyOf(60)).isEqualTo("어려움");
    }
}
