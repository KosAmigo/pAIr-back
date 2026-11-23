package com.pair.domain.task.service;

import com.pair.domain.task.entity.TaskRecord;
import com.pair.domain.task.repository.TaskRecordRepository;
import com.pair.domain.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과제 code(화면·API 에서 쓰는 이름) ↔ tasks.task_id(DB 외래 키) 변환과, 과제 행 → 과제 내용(TaskDef) 연결.
 * 실전 복사본(order-deadlock-live)은 내용 행이 따로 없어 원본(parent) code 로 내용을 찾는다.
 */
@Service
public class TaskCatalog {

    private final TaskRecordRepository records;
    private final TaskRepository definitions;

    public TaskCatalog(TaskRecordRepository records, TaskRepository definitions) {
        this.records = records;
        this.definitions = definitions;
    }

    @Transactional(readOnly = true)
    public TaskRecord require(String code) {
        return records.findByCode(code).orElseThrow(() -> new NoSuchElementException("없는 과제입니다: " + code));
    }

    @Transactional(readOnly = true)
    public TaskRecord require(Long id) {
        return records.findById(id).orElseThrow(() -> new NoSuchElementException("없는 과제입니다: " + id));
    }

    /** 과제 내용(설명·파일)이 있는 code. 복사본이면 원본 code */
    @Transactional(readOnly = true)
    public String definitionCode(TaskRecord t) {
        if (definitions.has(t.getCode()) || t.getParentTaskId() == null) return t.getCode();
        return require(t.getParentTaskId()).getCode();
    }

    @Transactional(readOnly = true)
    public TaskDef definition(String code) {
        return definitions.get(definitionCode(require(code)));
    }

    /** 응시자에게 보이는 과제 한 줄 (목록용). id 는 과제 code, type 은 dev | biz */
    public record Summary(String id, String type, String title, int minutes, TaskRecord.Status status) {}

    /**
     * 응시자에게 보이는 자가진단 과제 목록: 용도 SELF, 상태 OPEN(응시 가능)과 CLOSED(목록에만 보임). HIDDEN(검수 대기)과 실전(LIVE) 과제는 뺀다.
     * track 이 있으면(dev | biz, 대소문자 무시) 그 직군만.
     */
    @Transactional(readOnly = true)
    public List<Summary> selfTasks(String track) {
        return records.findByUsageAndStatusInOrderByIdAsc(TaskRecord.Usage.SELF, List.of(TaskRecord.Status.OPEN, TaskRecord.Status.CLOSED)).stream()
                .filter(t -> track == null || track.isBlank() || t.getTrack().equalsIgnoreCase(track))
                .map(t -> new Summary(t.getCode(), t.getTrack().toLowerCase(), t.getTitle(), t.getTimeLimitMin(), t.getStatus()))
                .toList();
    }

    /** 응시자에게 보여 줄 과제 상세. 목록에 나오지 않는 과제(실전 복사본, 검수 대기)는 없는 과제로 본다 */
    @Transactional(readOnly = true)
    public TaskDef publicSelfDefinition(String code) {
        TaskRecord t = require(code);
        if (t.getUsage() != TaskRecord.Usage.SELF || t.getStatus() == TaskRecord.Status.HIDDEN) {
            throw new NoSuchElementException("없는 과제입니다: " + code);
        }
        return definition(code);
    }

    /** code 목록 → 행 (순서 유지). 없는 code 면 NoSuchElementException */
    @Transactional(readOnly = true)
    public List<TaskRecord> requireAll(List<String> codes) {
        Map<String, TaskRecord> byCode = records.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(TaskRecord::getCode, Function.identity()));
        List<TaskRecord> out = new ArrayList<>();
        for (String c : codes) {
            TaskRecord t = byCode.get(c);
            if (t == null) throw new NoSuchElementException("없는 과제입니다: " + c);
            out.add(t);
        }
        return out;
    }

    /** task_id → code (여러 개를 한 번에) */
    @Transactional(readOnly = true)
    public Map<Long, String> codes(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return records.findAllById(ids).stream().collect(Collectors.toMap(TaskRecord::getId, TaskRecord::getCode));
    }

    /** 순서를 지킨 code 목록 */
    @Transactional(readOnly = true)
    public List<String> codesInOrder(List<Long> ids) {
        Map<Long, String> m = codes(ids);
        return ids.stream().map(m::get).toList();
    }
}
