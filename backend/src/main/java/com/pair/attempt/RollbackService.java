package com.pair.attempt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.attempt.db.CheckpointRecord;
import com.pair.attempt.db.CheckpointRecordRepository;

/**
 * 롤백: 체크포인트(질문 k)에서 갈라진 새 자가진단 응시를 만든다. 원래 응시는 그대로 둔다.
 * 새 응시는 질문 k 를 보낸 순간의 파일과 원래 응시의 1 ~ k-1 턴 대화(같은 턴 번호)를 가지고 풀이 중(SOLVING)으로 시작한다.
 * 롤백 횟수 제한은 없다. 파일·대화 복사까지 한 트랜잭션이다.
 * TODO: 크레딧 차감(ROLLBACK, 새 응시 ID 기준)은 팀원 1 의 크레딧 API 가 정해지면 이 트랜잭션 끝에서 호출한다.
 *       잔액이 모자라면 예외를 던져 위에서 만든 응시도 함께 롤백되게 한다.
 */
@Service
public class RollbackService {

    public record Result(String attemptId) {}

    private final CheckpointRecordRepository checkpoints;
    private final CheckpointService checkpointService;
    private final AttemptRecordRepository records;
    private final AttemptRegistry registry;
    private final AttemptStore store;

    public RollbackService(CheckpointRecordRepository checkpoints, CheckpointService checkpointService,
                           AttemptRecordRepository records, AttemptRegistry registry, AttemptStore store) {
        this.checkpoints = checkpoints;
        this.checkpointService = checkpointService;
        this.records = records;
        this.registry = registry;
        this.store = store;
    }

    @Transactional
    public Result rollback(Long userId, Long checkpointId) {
        CheckpointRecord cp = checkpoints.findById(checkpointId)
                .orElseThrow(() -> new NoSuchElementException("없는 체크포인트입니다."));
        AttemptRecord source = records.findById(cp.getAttemptId())
                .orElseThrow(() -> new NoSuchElementException("없는 체크포인트입니다."));
        // 남의 응시와 실전 응시는 있는지도 알려 주지 않는다
        if (source.getMode() != AttemptRecord.Mode.SELF || !userId.equals(source.getUserId())) {
            throw new NoSuchElementException("없는 체크포인트입니다.");
        }
        String sourceId = String.valueOf(source.getId());
        Attempt original = store.load(sourceId);

        AttemptRecord fork = registry.save(AttemptRecord.forkedSelf(userId, source.getTaskId(),
                source.getAssistantModel(), cp.getId(), Instant.now()));
        String forkId = String.valueOf(fork.getId());
        store.create(new Attempt(forkId, original.taskId(), source.getProvider(), source.getAssistantModel(),
                        fork.getStartedAt(), null, null, null, Attempt.Status.SOLVING, List.of(), null, null),
                checkpointService.restore(sourceId, cp.getTurnNo()));
        for (ChatTurn t : store.turns(sourceId)) {
            if (t.turn() < cp.getTurnNo()) store.appendTurn(forkId, t);
        }
        store.appendEvent(forkId, "start", Map.of("taskId", original.taskId(), "forkedFromCheckpointId", cp.getId()));
        return new Result(forkId);
    }
}
