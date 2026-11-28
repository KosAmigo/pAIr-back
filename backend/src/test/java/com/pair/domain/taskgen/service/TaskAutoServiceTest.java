package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskgen.dto.AutoTopicDto;
import com.pair.domain.taskgen.dto.TaskGenAutoRequest;
import com.pair.domain.taskgen.dto.TaskGenPreviewRequest;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskSuggestionService.Result;
import com.pair.domain.taskgen.service.TaskSuggestionService.Suggestion;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.AiUnavailableException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 자동 생성: 주제 미리보기(개수·함정 유형 배정·겹침 거르기·모자라면 한 번 더)와 시작(topics 그대로 / 서버가 고름) */
class TaskAutoServiceTest {

    static class FakeAi implements TaskGenAiService {
        final Deque<Result> script = new ArrayDeque<>();
        final List<String> users = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        final List<Integer> maxTokens = new ArrayList<>();

        @Override
        public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            return generate(model, system, user, type, 1000, "low");
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type, int max, String effort) {
            users.add(user);
            systems.add(system);
            maxTokens.add(max);
            Result r = script.poll();
            if (r == null) throw new AiUnavailableException("준비된 답이 없습니다.");
            return new Output<>((T) r, 100, 200);
        }
    }

    private FakeAi ai;
    private TaskRepository records;
    private TaskGenJobService jobs;
    private TaskAutoService auto;

    @BeforeEach
    void setUp() {
        ai = new FakeAi();
        records = mock(TaskRepository.class);
        when(records.findTop50ByOrderByIdDesc()).thenReturn(List.of());
        TrapTypeService trapTypes = mock(TrapTypeService.class);
        when(trapTypes.assign(anyString(), anyInt()))
                .thenAnswer(inv -> TrapTypeService.assign(TrapTypeService.types(inv.getArgument(0)), Map.of(), inv.getArgument(1)));
        jobs = mock(TaskGenJobService.class);
        TaskSuggestionService suggestions = new TaskSuggestionService(ai, new TaskGenProperties(null, null, null, null, null), records, Clock.systemUTC());
        auto = new TaskAutoService(suggestions, trapTypes, jobs);
    }

    private static Suggestion s(String situation) {
        return new Suggestion(situation, situation + " 함정", "AI가 쓴 유형", List.of("usage"), "이유");
    }

    private static Result result(String... situations) {
        return new Result(null, Arrays.stream(situations).map(TaskAutoServiceTest::s).toList());
    }

    private static Task task(String title) {
        Task t = mock(Task.class);
        when(t.getTitle()).thenReturn(title);
        when(t.getSummary()).thenReturn("");
        return t;
    }

    @Test
    @DisplayName("미리보기: count 개를 싼 모델 1번으로 받고, 함정 유형은 코드가 배정한 값(R1, R2, ...)으로 채운다")
    void previewAssignsTypes() {
        ai.script.add(result("쿠폰 할인 계산", "환불 가능 기간 판정", "정산 마감 시각 처리", "포인트 적립 반올림"));
        List<AutoTopicDto> out = auto.preview(new TaskGenPreviewRequest("dev", 40, 4, List.of()), 1L);

        assertThat(out).extracting(AutoTopicDto::trapType).containsExactly("R1 경계 포함 여부", "R2 업무 기준 시간대·마감", "R3 회사 반올림·절사 규칙", "R4 적용 순서");
        assertThat(out.get(0).situation()).isEqualTo("쿠폰 할인 계산");
        assertThat(out.get(0).competencies()).containsExactly("usage");
        assertThat(ai.users).hasSize(1);
        assertThat(ai.users.get(0)).contains("40분").contains("4개");
        assertThat(ai.systems.get(0)).contains("R4 적용 순서").contains("정확히 4개");
        assertThat(ai.maxTokens.get(0)).isGreaterThanOrEqualTo(4 * 450); // 주제가 많으면 출력 한도도 늘린다
    }

    @Test
    @DisplayName("기존 과제·exclude·서로 겹치는 주제는 코드가 거르고, 모자라면 모자란 칸만 한 번 더 받는다")
    void previewFiltersOverlapAndRetriesOnce() {
        Task existing = task("쿠폰 할인 계산"); // 목 만들기는 when 안에서 하면 안 된다
        when(records.findTop50ByOrderByIdDesc()).thenReturn(List.of(existing));
        // 1칸째는 기존 과제와 겹침, 3칸째는 2칸째와 겹침, 4칸째는 exclude 와 같음 → 2칸째만 남는다
        ai.script.add(result("쿠폰 할인 계산", "환불 가능 기간 판정", "환불 가능 기간 판정 규칙", "방금 본 주제"));
        ai.script.add(result("정산 마감 시각 처리", "포인트 적립 반올림", "배송비 면제 기준"));
        List<AutoTopicDto> out = auto.preview(new TaskGenPreviewRequest("DEV", null, 4, List.of("방금 본 주제")), 1L);

        assertThat(ai.users).hasSize(2); // 거르고 모자라서 한 번 더(두 번까지만)
        assertThat(out).extracting(AutoTopicDto::situation).containsExactly("정산 마감 시각 처리", "환불 가능 기간 판정", "포인트 적립 반올림", "배송비 면제 기준");
        // 빈 칸이던 R1·R3·R4 를 두 번째 호출이 순서대로 채우고, 유지된 2칸째는 R2 그대로
        assertThat(out).extracting(AutoTopicDto::trapType).containsExactly("R1 경계 포함 여부", "R2 업무 기준 시간대·마감", "R3 회사 반올림·절사 규칙", "R4 적용 순서");
        assertThat(ai.users.get(1)).contains("3개").contains("쿠폰 할인 계산").contains("환불 가능 기간 판정");
        assertThat(ai.systems.get(1)).contains("R1 경계 포함 여부").contains("R3 회사 반올림·절사 규칙").doesNotContain("R2 업무 기준");
    }

    @Test
    @DisplayName("20개는 한 호출에 10개씩 두 번 나눠 받고, 두 번째 호출은 첫 묶음 주제를 피하게 알려 준다")
    void previewTwentySplitsIntoTwoCalls() {
        String[] a = {"쿠폰 할인 계산", "환불 기간 판정", "정산 마감 처리", "포인트 반올림", "배송비 면제", "재고 차감 순서", "회원 등급 산정", "이자 일할 계산", "근무 시간 집계", "환율 변환"};
        String[] b = {"주차 요금 정산", "세금 공제 한도", "예약 중복 확인", "택배 지연 보상", "연차 잔여 일수", "통신 요금 할인", "보험료 갱신", "급여 원천징수", "렌탈 위약금", "마일리지 소멸"};
        ai.script.add(result(a));
        ai.script.add(result(b));
        List<AutoTopicDto> out = auto.preview(new TaskGenPreviewRequest("DEV", 60, 20, null), 1L);

        assertThat(out).hasSize(20);
        assertThat(ai.users).hasSize(2);
        assertThat(ai.systems.get(0)).contains("정확히 10개");
        assertThat(ai.users.get(1)).contains("10개").contains("쿠폰 할인 계산").contains("환율 변환");
        assertThat(ai.maxTokens).allSatisfy(m -> assertThat(m).isGreaterThanOrEqualTo(10 * 450));
        assertThat(out.get(10).situation()).isEqualTo("주차 요금 정산");
    }

    @Test
    @DisplayName("모델이 한 개도 못 주면 AiUnavailable, 입력이 잘못되면 IllegalArgument")
    void previewErrors() {
        assertThatThrownBy(() -> auto.preview(new TaskGenPreviewRequest("DEV", 60, 3, null), 1L)).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> auto.preview(new TaskGenPreviewRequest("DEV", 60, 0, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auto.preview(new TaskGenPreviewRequest("DEV", 60, 21, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auto.preview(new TaskGenPreviewRequest("DEV", 30, 3, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auto.preview(new TaskGenPreviewRequest("XYZ", 60, 3, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> auto.preview(null, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("자동 시작: topics 가 있으면 AI 를 부르지 않고 그대로 작업을 만든다(함정 앞에 [유형] 표시)")
    void startWithGivenTopics() {
        when(jobs.startAll(anyList(), eq(7L), eq("BATCH"), eq(3))).thenReturn(new TaskGenJobService.Batch("b-1", List.of("j1", "j2")));
        var topics = List.of(new AutoTopicDto("환불 기간", "당일 포함 여부를 놓침", "R1 경계 포함 여부", List.of("control")),
                new AutoTopicDto("정산 마감", "마감 시각을 놓침", null, List.of("usage")));
        var b = auto.start(new TaskGenAutoRequest("dev", 20, null, 5, "BATCH", 3, topics), 7L);

        assertThat(b.jobIds()).containsExactly("j1", "j2");
        assertThat(ai.users).isEmpty();
        List<TaskGenRequest> forms = captureForms(7L, "BATCH", 3);
        assertThat(forms).hasSize(2);
        assertThat(forms.get(0).topic()).isEqualTo("환불 기간");
        assertThat(forms.get(0).trap()).isEqualTo("[R1 경계 포함 여부] 당일 포함 여부를 놓침");
        assertThat(forms.get(0).track()).isEqualTo("DEV");
        assertThat(forms.get(0).minutes()).isEqualTo(20);
        assertThat(forms.get(0).usage()).isEqualTo("SELF");
        assertThat(forms.get(1).trap()).isEqualTo("마감 시각을 놓침");
    }

    @Test
    @DisplayName("자동 시작: topics 가 없으면 서버가 미리보기와 같은 방법으로 count 개를 골라 작업을 만든다")
    void startPicksTopicsWhenMissing() {
        ai.script.add(result("주제 하나", "주제 둘"));
        when(jobs.startAll(anyList(), anyLong(), any(), any())).thenReturn(new TaskGenJobService.Batch("b-2", List.of("j1", "j2")));
        auto.start(new TaskGenAutoRequest("BIZ", 60, "LIVE", 2, null, null, null), 1L);

        assertThat(ai.users).hasSize(1);
        List<TaskGenRequest> forms = captureForms(1L, null, null);
        assertThat(forms).extracting(TaskGenRequest::topic).containsExactly("주제 하나", "주제 둘");
        assertThat(forms.get(0).trap()).startsWith("[B1 심사 지표 정의] "); // BIZ 는 B 유형
        assertThat(forms).extracting(TaskGenRequest::usage).containsOnly("LIVE");
    }

    @SuppressWarnings("unchecked")
    private List<TaskGenRequest> captureForms(long admin, String mode, Integer hours) {
        ArgumentCaptor<List<TaskGenRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobs).startAll(captor.capture(), eq(admin), mode == null ? isNull() : eq(mode), hours == null ? isNull() : eq(hours));
        return captor.getValue();
    }

    @Test
    @DisplayName("겹침 판정: 공백·기호를 빼고 서로 들어 있거나 두 글자 조각이 많이 겹치면 같은 주제")
    void overlapRule() {
        assertThat(TaskSuggestionService.overlaps("쿠폰 할인 계산", "쿠폰할인계산")).isTrue();
        assertThat(TaskSuggestionService.overlaps("쿠폰 할인 계산 규칙", "쿠폰 할인 계산")).isTrue();
        assertThat(TaskSuggestionService.overlaps("환불 가능 기간 판정", "정산 마감 시각 처리")).isFalse();
        assertThat(TaskSuggestionService.overlaps("", "쿠폰")).isFalse();
        assertThat(TaskSuggestionService.overlaps(null, null)).isFalse();
    }
}
