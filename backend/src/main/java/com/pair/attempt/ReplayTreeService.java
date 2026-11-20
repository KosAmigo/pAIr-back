package com.pair.attempt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.attempt.db.CheckpointRecord;
import com.pair.attempt.db.CheckpointRecordRepository;

/**
 * 복기 트리: 노드는 응시, 간선은 attempts.forked_from_checkpoint_id → checkpoints.attempt_id.
 * 응시 하나를 주면 그 응시가 속한 트리 전체(뿌리부터 모든 갈래)를 평평한 목록으로 돌려준다. 화면이 parent 로 트리를 세운다.
 */
@Service
public class ReplayTreeService {

    /** parentAttemptId·forkedAtTurn·forkedFromCheckpointId 는 뿌리(원래 응시)만 null */
    public record Node(String attemptId, String parentAttemptId, Integer forkedAtTurn, Long forkedFromCheckpointId,
                       Attempt.Status status, Instant startedAt) {}

    private final AttemptRecordRepository records;
    private final CheckpointRecordRepository checkpoints;

    public ReplayTreeService(AttemptRecordRepository records, CheckpointRecordRepository checkpoints) {
        this.records = records;
        this.checkpoints = checkpoints;
    }

    @Transactional(readOnly = true)
    public List<Node> tree(Long userId, String attemptId) {
        AttemptRecord current = find(attemptId);
        // 남의 응시와 실전 응시는 있는지도 알려 주지 않는다
        if (current.getMode() != AttemptRecord.Mode.SELF || !userId.equals(current.getUserId())) {
            throw new NoSuchElementException("없는 응시입니다: " + attemptId);
        }
        AttemptRecord root = current;
        while (root.getForkedFromCheckpointId() != null) {
            CheckpointRecord parent = checkpoints.findById(root.getForkedFromCheckpointId()).orElseThrow();
            root = records.findById(parent.getAttemptId()).orElseThrow();
        }

        List<Node> out = new ArrayList<>();
        out.add(new Node(String.valueOf(root.getId()), null, null, null, root.getStatus(), root.getStartedAt()));
        List<Long> level = List.of(root.getId());
        while (!level.isEmpty()) {
            Map<Long, CheckpointRecord> cps = checkpoints.findByAttemptIdIn(level).stream()
                    .collect(Collectors.toMap(CheckpointRecord::getId, Function.identity()));
            if (cps.isEmpty()) break;
            List<AttemptRecord> children = records.findByForkedFromCheckpointIdIn(cps.keySet());
            for (AttemptRecord c : children) {
                CheckpointRecord cp = cps.get(c.getForkedFromCheckpointId());
                out.add(new Node(String.valueOf(c.getId()), String.valueOf(cp.getAttemptId()), cp.getTurnNo(), cp.getId(),
                        c.getStatus(), c.getStartedAt()));
            }
            level = children.stream().map(AttemptRecord::getId).toList();
        }
        return out;
    }

    private AttemptRecord find(String attemptId) {
        Long id = AttemptRegistry.parse(attemptId);
        return (id == null ? java.util.Optional.<AttemptRecord>empty() : records.findById(id))
                .orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + attemptId));
    }
}
