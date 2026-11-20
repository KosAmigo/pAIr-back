package com.pair.attempt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.attempt.db.CheckpointFileRecord;
import com.pair.attempt.db.CheckpointFileRecordRepository;
import com.pair.attempt.db.CheckpointRecord;
import com.pair.attempt.db.CheckpointRecordRepository;
import com.pair.task.TaskRepository;

/**
 * 체크포인트: 질문을 보낼 때마다 그 순간의 파일 상태를 남긴다. 바로 앞 체크포인트(첫 번째는 과제 원본)와
 * 내용이 다른 파일만 저장하고, 질문 k 시점 복원은 과제 원본 위에 turn_no ≤ k 인 변경분을 순서대로 덮어 만든다.
 */
@Service
public class CheckpointService {

    /** checkpoints.label 길이 (VARCHAR 100) */
    static final int MAX_LABEL = 100;

    public record View(Long checkpointId, int turnNo, String label, Instant createdAt) {}

    private final AttemptStore store;
    private final TaskRepository tasks;
    private final CheckpointRecordRepository checkpoints;
    private final CheckpointFileRecordRepository checkpointFiles;
    private final AttemptRecordRepository records;

    public CheckpointService(AttemptStore store, TaskRepository tasks, CheckpointRecordRepository checkpoints,
                             CheckpointFileRecordRepository checkpointFiles, AttemptRecordRepository records) {
        this.store = store;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.checkpointFiles = checkpointFiles;
        this.records = records;
    }

    /** 질문 턴과 그 체크포인트를 한 트랜잭션에 저장한다. files: 질문을 보낸 순간의 작업 파일 전체 */
    @Transactional
    public void appendQuestion(String attemptId, ChatTurn question, Map<String, String> files) {
        store.appendTurn(attemptId, question);
        Long id = Long.valueOf(attemptId);
        Map<String, String> before = restore(attemptId, Integer.MAX_VALUE);
        CheckpointRecord cp = checkpoints.save(new CheckpointRecord(id, question.turn(), Instant.now()));
        List<CheckpointFileRecord> changed = files.entrySet().stream()
                .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                .map(e -> new CheckpointFileRecord(cp.getId(), e.getKey(), e.getValue()))
                .toList();
        checkpointFiles.saveAll(changed);
    }

    /** 질문 upTo 시점의 작업 파일 (경로 → 내용) */
    @Transactional(readOnly = true)
    public Map<String, String> restore(String attemptId, int upTo) {
        Map<String, String> out = new TreeMap<>(tasks.visibleFiles(store.load(attemptId).taskId()));
        for (CheckpointFileRecord f : checkpointFiles.findUpTo(Long.valueOf(attemptId), upTo)) {
            out.put(f.getPath(), f.getContent());
        }
        return out;
    }

    /** 내 체크포인트에 이름을 붙인다. 비어 있으면 이름을 지운다. 남의 체크포인트는 있는지도 알려 주지 않는다 */
    @Transactional
    public View rename(Long userId, Long checkpointId, String label) {
        CheckpointRecord cp = checkpoints.findById(checkpointId)
                .orElseThrow(() -> new NoSuchElementException("없는 체크포인트입니다."));
        AttemptRecord attempt = records.findById(cp.getAttemptId()).orElseThrow();
        if (!userId.equals(attempt.getUserId())) throw new NoSuchElementException("없는 체크포인트입니다.");
        String name = label == null ? "" : label.strip();
        if (name.length() > MAX_LABEL) throw new IllegalArgumentException("이름은 " + MAX_LABEL + "자 이하로 입력하세요.");
        cp.rename(name.isEmpty() ? null : name);
        return new View(cp.getId(), cp.getTurnNo(), cp.getLabel(), cp.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<View> list(String attemptId) {
        return checkpoints.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(attemptId)).stream()
                .map(c -> new View(c.getId(), c.getTurnNo(), c.getLabel(), c.getCreatedAt())).toList();
    }
}
