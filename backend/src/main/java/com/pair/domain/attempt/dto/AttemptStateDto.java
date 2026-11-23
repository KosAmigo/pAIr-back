package com.pair.domain.attempt.dto;

import java.time.Instant;
import java.util.List;

/** 응시 1건의 상태. DB attempts 행 (채점 툴 샘플은 grader/samples/{id}/attempt.json) */
public record AttemptStateDto(
        String id,
        String taskId,
        String provider,
        String model,
        Instant startedAt,
        Instant submittedAt,
        Instant followupsDoneAt,
        Instant firstValidAt,
        Status status,
        List<String> followUpAnswers,
        String error,
        /** 답할 때 보인 후속 질문 문구. 과제의 질문이 나중에 바뀌어도 채점은 응시자가 본 질문으로 한다. */
        List<String> followUpQuestions) {

    public enum Status { SOLVING, FOLLOWUP, SCORING, DONE, ERROR }

    public AttemptStateDto withStatus(Status s) {
        return new AttemptStateDto(id, taskId, provider, model, startedAt, submittedAt, followupsDoneAt, firstValidAt, s, followUpAnswers, error, followUpQuestions);
    }

    public AttemptStateDto withSubmitted(Instant at) {
        return new AttemptStateDto(id, taskId, provider, model, startedAt, at, followupsDoneAt, firstValidAt, Status.FOLLOWUP, followUpAnswers, error, followUpQuestions);
    }

    public AttemptStateDto withFollowUps(List<String> answers, List<String> questions, Instant at) {
        return new AttemptStateDto(id, taskId, provider, model, startedAt, submittedAt, at, firstValidAt, Status.SCORING, answers, error, questions);
    }

    public AttemptStateDto withFollowUps(List<String> answers, Instant at) {
        return new AttemptStateDto(id, taskId, provider, model, startedAt, submittedAt, at, firstValidAt, Status.SCORING, answers, error, followUpQuestions);
    }

    public AttemptStateDto withFirstValid(Instant at) {
        return firstValidAt != null ? this
                : new AttemptStateDto(id, taskId, provider, model, startedAt, submittedAt, followupsDoneAt, at, status, followUpAnswers, error, followUpQuestions);
    }

    public AttemptStateDto withError(String message) {
        return new AttemptStateDto(id, taskId, provider, model, startedAt, submittedAt, followupsDoneAt, firstValidAt, Status.ERROR, followUpAnswers, message, followUpQuestions);
    }
}
