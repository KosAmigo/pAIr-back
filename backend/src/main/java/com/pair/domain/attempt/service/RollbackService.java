package com.pair.domain.attempt.service;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.dto.ChatTurnStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.attempt.entity.Checkpoint;
import com.pair.domain.attempt.repository.CheckpointRepository;
import com.pair.domain.credit.service.CreditService;

/**
 * 롤백: 체크포인트(질문 k)에서 갈라진 새 자가진단 응시를 만든다. 원래 응시는 그대로 둔다.
 * 새 응시는 질문 k 를 보낸 순간의 파일과 원래 응시의 1 ~ k-1 턴 대화(같은 턴 번호)를 가지고 풀이 중(SOLVING)으로 시작한다.
 * 롤백 횟수 제한은 없다. 파일·대화 복사까지 한 트랜잭션이다.
 */
@Service
public class RollbackService {

    public record Result(String attemptId) {}

    private final CheckpointRepository checkpoints;
    private final CheckpointService checkpointService;
    private final AttemptRepository records;
    private final AttemptRegistryService registry;
    private final AttemptStateRepository store;
    private final CreditService credits;
    private final ChatAttachmentService attachments;

    public RollbackService(CheckpointRepository checkpoints, CheckpointService checkpointService,
                           AttemptRepository records, AttemptRegistryService registry, AttemptStateRepository store, CreditService credits,
                           ChatAttachmentService attachments) {
        this.checkpoints = checkpoints;
        this.checkpointService = checkpointService;
        this.records = records;
        this.registry = registry;
        this.store = store;
        this.credits = credits;
        this.attachments = attachments;
    }

    @Transactional
    public Result rollback(Long userId, Long checkpointId) {
        Checkpoint cp = checkpoints.findById(checkpointId)
                .orElseThrow(() -> new NoSuchElementException("없는 체크포인트입니다."));
        Attempt source = records.findById(cp.getAttemptId())
                .orElseThrow(() -> new NoSuchElementException("없는 체크포인트입니다."));
        // 남의 응시와 실전 응시는 있는지도 알려 주지 않는다
        if (source.isDeleted() || source.getMode() != Attempt.Mode.SELF || !userId.equals(source.getUserId())) {
            throw new NoSuchElementException("없는 체크포인트입니다.");
        }
        credits.checkRollback(userId);
        String sourceId = String.valueOf(source.getId());
        AttemptStateDto original = store.load(sourceId);

        Attempt fork = registry.save(Attempt.forkedSelf(userId, source.getTaskId(),
                source.getAssistantModel(), cp.getId(), Instant.now()));
        String forkId = String.valueOf(fork.getId());
        store.create(new AttemptStateDto(forkId, original.taskId(), source.getProvider(), source.getAssistantModel(),
                        fork.getStartedAt(), null, null, null, AttemptStateDto.Status.SOLVING, List.of(), null, null),
                checkpointService.restore(sourceId, cp.getTurnNo()));
        for (ChatTurnStateDto t : store.turns(sourceId)) {
            if (t.turn() < cp.getTurnNo()) store.appendTurn(forkId, t);
        }
        // 복사한 질문 턴의 첨부도 함께 복사한다 (크레딧은 다시 차감하지 않는다)
        attachments.copyForFork(source.getId(), fork.getId(), cp.getTurnNo());
        store.appendEvent(forkId, "start", Map.of("taskId", original.taskId(), "forkedFromCheckpointId", cp.getId()));
        credits.chargeRollback(userId, fork.getId());
        return new Result(forkId);
    }
}
