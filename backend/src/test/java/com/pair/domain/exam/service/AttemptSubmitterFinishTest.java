package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.repository.AttemptStateRepository;

/** 종료 시각에 풀이·후속 질문 중이던 응시를 끝까지 넘기는 AttemptSubmitService.finishAll */
class AttemptSubmitterFinishTest {

    private AttemptService service;
    private AttemptStateRepository store;
    private AttemptSubmitService submitter;

    @BeforeEach
    void setUp() {
        service = mock(AttemptService.class);
        store = mock(AttemptStateRepository.class);
        submitter = new AttemptSubmitService(service, store);
    }

    private AttemptStateDto attempt(String id, AttemptStateDto.Status status) {
        return new AttemptStateDto(id, "task", null, null, Instant.EPOCH, null, null, null, status, List.of(), null, List.of());
    }

    @Test
    @DisplayName("후속 질문 단계면 빈 답으로 마무리해 채점으로 넘긴다")
    void finishAll_followUp_completesWithEmptyAnswers() {
        when(store.load("1")).thenReturn(attempt("1", AttemptStateDto.Status.FOLLOWUP));

        int failed = submitter.finishAll(List.of("1"));

        assertThat(failed).isZero();
        verify(service).followUps("1", List.of());
    }

    @Test
    @DisplayName("풀이 중이면 먼저 제출하고 이어서 후속 질문을 마무리한다")
    void finishAll_solving_submitsThenCompletes() {
        when(store.load("2")).thenReturn(attempt("2", AttemptStateDto.Status.SOLVING), attempt("2", AttemptStateDto.Status.FOLLOWUP));

        submitter.finishAll(List.of("2"));

        verify(service).submit("2");
        verify(service).followUps("2", List.of());
    }

    @Test
    @DisplayName("이미 채점 단계로 간 응시는 건드리지 않는다")
    void finishAll_alreadyScoring_untouched() {
        when(store.load("3")).thenReturn(attempt("3", AttemptStateDto.Status.SCORING));
        when(store.load("4")).thenReturn(attempt("4", AttemptStateDto.Status.DONE));

        int failed = submitter.finishAll(List.of("3", "4"));

        assertThat(failed).isZero();
        verify(service, never()).submit(anyString());
        verify(service, never()).followUps(anyString(), anyList());
    }

    @Test
    @DisplayName("한 응시가 실패해도 나머지는 계속하고 실패 수를 돌려준다")
    void finishAll_oneFails_othersContinue() {
        when(store.load("5")).thenReturn(attempt("5", AttemptStateDto.Status.FOLLOWUP));
        when(store.load("6")).thenReturn(attempt("6", AttemptStateDto.Status.FOLLOWUP));
        doThrow(new IllegalStateException("깨진 응시")).when(service).followUps("5", List.of());

        int failed = submitter.finishAll(List.of("5", "6"));

        assertThat(failed).isEqualTo(1);
        verify(service).followUps("6", List.of());
    }
}
