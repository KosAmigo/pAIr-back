package com.pair.exam.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptRecordRepository;
import com.pair.exam.domain.ExamParticipant;
import com.pair.task.TaskCatalog;

/**
 * 대상자의 시험 진행 상태. ERD 에서는 시작·제출 시각이 대상자가 아니라 과제별 응시(attempts)에 있어서 거기서 계산한다.
 *   시작 = 첫 응시 시작 시각 (시험 시작 때 과제마다 응시를 한꺼번에 만든다)
 *   제출 = 회차의 모든 과제를 제출했으면 마지막 제출 시각
 */
@Component
public class ParticipantProgress {

    /**
     * attempts: 과제 code → 응시 ID, submitted: 과제 code → 제출했는지, records: 응시 행 (과제 순서)
     */
    public record Progress(Instant startedAt, Instant submittedAt, Map<String, String> attempts, Map<String, Boolean> submitted,
            List<AttemptRecord> records) {

        static final Progress NONE = new Progress(null, null, Map.of(), Map.of(), List.of());

        public boolean started() {
            return startedAt != null;
        }

        public boolean finished() {
            return submittedAt != null;
        }
    }

    private final AttemptRecordRepository records;
    private final TaskCatalog catalog;

    public ParticipantProgress(AttemptRecordRepository records, TaskCatalog catalog) {
        this.records = records;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Progress of(ExamParticipant p) {
        return of(List.of(p)).get(p.getId());
    }

    /** 여러 대상자를 한 번에 (응시·과제 이름을 대상자마다 따로 읽지 않는다) */
    @Transactional(readOnly = true)
    public Map<Long, Progress> of(Collection<ExamParticipant> ps) {
        if (ps.isEmpty()) return Map.of();
        List<AttemptRecord> all = records.findByParticipantIdIn(ps.stream().map(ExamParticipant::getId).toList());
        Map<Long, String> codes = catalog.codes(all.stream().map(AttemptRecord::getTaskId).distinct().toList());
        Map<Long, List<AttemptRecord>> byParticipant = all.stream().collect(Collectors.groupingBy(AttemptRecord::getParticipantId));
        Map<Long, Progress> out = new HashMap<>();
        for (ExamParticipant p : ps) {
            List<AttemptRecord> rs = byParticipant.getOrDefault(p.getId(), List.of());
            out.put(p.getId(), rs.isEmpty() ? Progress.NONE : build(rs, codes, p.getSession().getTaskIds()));
        }
        return out;
    }

    private static Progress build(List<AttemptRecord> rs, Map<Long, String> codes, List<Long> sessionTasks) {
        List<AttemptRecord> sorted = rs.stream()
                .sorted(Comparator.comparingInt((AttemptRecord r) -> order(sessionTasks, r.getTaskId())).thenComparing(AttemptRecord::getId))
                .toList();
        Map<String, String> attempts = new LinkedHashMap<>();
        Map<String, Boolean> submitted = new LinkedHashMap<>();
        for (AttemptRecord r : sorted) {
            String code = codes.get(r.getTaskId());
            attempts.put(code, String.valueOf(r.getId()));
            submitted.put(code, r.isSubmitted());
        }
        Instant started = sorted.stream().map(AttemptRecord::getStartedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        boolean all = sorted.size() >= sessionTasks.size() && sorted.stream().allMatch(AttemptRecord::isSubmitted);
        Instant done = all ? sorted.stream().map(AttemptRecord::getSubmittedAt).max(Comparator.naturalOrder()).orElse(null) : null;
        return new Progress(started, done, Collections.unmodifiableMap(attempts), Collections.unmodifiableMap(submitted), sorted);
    }

    private static int order(List<Long> sessionTasks, Long taskId) {
        int i = sessionTasks.indexOf(taskId);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
