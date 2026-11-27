package com.pair.domain.attempt.service;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.entity.Checkpoint;
import com.pair.domain.attempt.repository.CheckpointRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.error.ForbiddenException;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.DbAttemptStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응시 행(DB attempts)을 만들고 찾는다. 응시 ID 는 DB 가 만든 attempt_id 를 문자열로 쓴다.
 * 응시 상태·작업 파일·대화 등 나머지는 DbAttemptStateRepository 가 같은 행과 하위 테이블에 저장한다.
 */
@Component
public class AttemptRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AttemptRegistryService.class);

    private final AttemptRepository records;
    private final CheckpointRepository checkpoints;

    public AttemptRegistryService(AttemptRepository records, CheckpointRepository checkpoints) {
        this.records = records;
        this.checkpoints = checkpoints;
    }

    /**
     * 복기에서 지우기: 제출하지 않은 자가진단 응시만. 주인 확인은 AttemptOwnerInterceptor 가 한다.
     * 실전 응시자도 이 주소(/api/attempts/{id})는 통과하므로 여기서 막는다. 갈래가 남아 있으면 복기 트리가 끊겨 막는다.
     * 크레딧은 돌려주지 않는다.
     */
    @Transactional
    public void delete(String attemptId) {
        Attempt r = find(attemptId).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + attemptId));
        if (r.getMode() != Attempt.Mode.SELF) throw new ForbiddenException("실전 시험 응시는 삭제할 수 없습니다.");
        List<Long> checkpointIds = checkpoints.findByAttemptIdIn(List.of(r.getId())).stream().map(Checkpoint::getId).toList();
        if (!checkpointIds.isEmpty()
                && records.findByForkedFromCheckpointIdIn(checkpointIds).stream().anyMatch(c -> !c.isDeleted())) {
            throw new ConflictException("이 풀이에서 갈라진 갈래가 있어 삭제할 수 없습니다.");
        }
        if (records.softDelete(r.getId(), Instant.now()) == 0) {
            throw new ConflictException("제출하지 않은 풀이만 삭제할 수 있습니다.");
        }
    }

    public Attempt save(Attempt r) {
        return records.save(r);
    }

    public Optional<Attempt> find(String attemptId) {
        Long id = parse(attemptId);
        return id == null ? Optional.empty() : records.findById(id);
    }

    /** 회차 제출: 응시 화면 제출이 실패해도 제출 시각은 남긴다 (두 번 불러도 안전) */
    public void markSubmitted(String attemptId, Instant at) {
        find(attemptId).ifPresentOrElse(r -> {
            r.sync(AttemptStateDto.Status.FOLLOWUP, at, null);
            records.save(r);
        }, () -> log.warn("DB 에 없는 응시를 제출하려 함: {}", attemptId));
    }

    /**
     * 후속 질문 마무리를 차지한다: FOLLOWUP 인 응시만 SCORING 으로 바꾼다(DB 에서 한 번에).
     * @return 이 요청이 바꿨으면 true, 다른 요청이 먼저 바꿨으면 false. DB 응시가 아니면(숫자 ID 아님) 막을 것이 없어 true
     */
    public boolean startScoring(String attemptId, Instant at) {
        Long id = parse(attemptId);
        return id == null || records.startScoring(id, at) > 0;
    }

    public static Long parse(String attemptId) {
        if (attemptId == null || !attemptId.matches("\\d{1,18}")) return null;
        return Long.valueOf(attemptId);
    }
}
