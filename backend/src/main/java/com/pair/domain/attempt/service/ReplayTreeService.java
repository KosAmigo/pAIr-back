package com.pair.domain.attempt.service;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.pair.domain.attempt.entity.Checkpoint;
import com.pair.domain.attempt.repository.CheckpointRepository;

/**
 * 복기 트리: 노드는 응시, 간선은 attempts.forked_from_checkpoint_id → checkpoints.attempt_id.
 * 응시 하나를 주면 그 응시가 속한 트리 전체(뿌리부터 모든 갈래)를 평평한 목록으로 돌려준다. 화면이 parent 로 트리를 세운다.
 */
@Service
public class ReplayTreeService {

    /** 항목 하나의 점수 (리포트의 items) */
    public record ItemScore(String key, String name, int max, Integer score) {}

    /** 채점 점수. 두 갈래를 나란히 비교하는 데 쓴다 */
    public record Score(int total, int max, List<ItemScore> items) {}

    /**
     * parentAttemptId·forkedAtTurn·forkedFromCheckpointId 는 뿌리(원래 응시)만 null.
     * score 는 채점이 끝난(DONE) 응시만 있고, 아직 채점 전이거나 이전 형식 리포트(다시 채점 필요)면 null.
     */
    public record Node(String attemptId, String parentAttemptId, Integer forkedAtTurn, Long forkedFromCheckpointId,
                       AttemptState.Status status, Instant startedAt, Score score) {}

    private final AttemptRepository records;
    private final CheckpointRepository checkpoints;
    private final ReplayReports reports;

    public ReplayTreeService(AttemptRepository records, CheckpointRepository checkpoints, ReplayReports reports) {
        this.records = records;
        this.checkpoints = checkpoints;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public List<Node> tree(Long userId, String attemptId) {
        Attempt current = find(attemptId);
        // 남의 응시와 실전 응시는 있는지도 알려 주지 않는다
        if (current.getMode() != Attempt.Mode.SELF || !userId.equals(current.getUserId())) {
            throw new NoSuchElementException("없는 응시입니다: " + attemptId);
        }
        Attempt root = current;
        while (root.getForkedFromCheckpointId() != null) {
            Checkpoint parent = checkpoints.findById(root.getForkedFromCheckpointId()).orElseThrow();
            root = records.findById(parent.getAttemptId()).orElseThrow();
        }

        List<Node> out = new ArrayList<>();
        out.add(new Node(String.valueOf(root.getId()), null, null, null, root.getStatus(), root.getStartedAt(), scoreOf(root)));
        List<Long> level = List.of(root.getId());
        while (!level.isEmpty()) {
            Map<Long, Checkpoint> cps = checkpoints.findByAttemptIdIn(level).stream()
                    .collect(Collectors.toMap(Checkpoint::getId, Function.identity()));
            if (cps.isEmpty()) break;
            List<Attempt> children = records.findByForkedFromCheckpointIdIn(cps.keySet());
            for (Attempt c : children) {
                Checkpoint cp = cps.get(c.getForkedFromCheckpointId());
                out.add(new Node(String.valueOf(c.getId()), String.valueOf(cp.getAttemptId()), cp.getTurnNo(), cp.getId(),
                        c.getStatus(), c.getStartedAt(), scoreOf(c)));
            }
            level = children.stream().map(Attempt::getId).toList();
        }
        return out;
    }

    /** 트리 노드의 점수(팀원 2 의 리포트: total, max, items[key,name,max,score]). 읽을 수 있는 리포트가 없어도 트리는 보여 준다 */
    private Score scoreOf(Attempt r) {
        JsonNode rep = reports.scored(r).orElse(null);
        if (rep == null) return null;
        List<ItemScore> items = new ArrayList<>();
        for (JsonNode it : rep.path("items")) {
            JsonNode sc = it.path("score");
            items.add(new ItemScore(it.path("key").asText(), it.path("name").asText(), it.path("max").asInt(),
                    sc.isNumber() ? sc.asInt() : null));
        }
        return new Score(rep.path("total").asInt(), rep.path("max").asInt(), items);
    }

    private Attempt find(String attemptId) {
        Long id = AttemptRegistry.parse(attemptId);
        return (id == null ? java.util.Optional.<Attempt>empty() : records.findById(id))
                .orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + attemptId));
    }
}
