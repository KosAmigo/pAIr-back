package com.pair.attempt;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 복기에서 채점 리포트를 읽는 유일한 곳. 팀원 2 의 리포트 형식이 바뀌면 여기와 이 리포트를 해석하는 곳만 고친다.
 * 채점이 끝난(DONE) 응시의 version 2(감점식) 리포트만 돌려준다. 채점 전이거나 이전 형식(다시 채점 필요)이거나 읽지 못하면 empty.
 */
@Component
class ReplayReports {

    private final AttemptStore store;

    ReplayReports(AttemptStore store) {
        this.store = store;
    }

    Optional<JsonNode> scored(AttemptRecord r) {
        if (r.getStatus() != Attempt.Status.DONE) return Optional.empty();
        JsonNode rep;
        try {
            rep = store.readReport(String.valueOf(r.getId()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return rep != null && rep.path("version").asInt() >= 2 ? Optional.of(rep) : Optional.empty();
    }
}
