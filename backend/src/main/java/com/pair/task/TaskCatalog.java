package com.pair.task;

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
