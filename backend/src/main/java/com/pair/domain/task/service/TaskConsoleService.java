package com.pair.domain.task.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.entity.TaskFileRecord;
import com.pair.domain.task.repository.TaskFileRecordRepository;
import com.pair.domain.taskgen.service.GeneratedTaskWriter;
import com.pair.global.error.ConflictException;
import com.pair.domain.task.entity.TaskRecord;
import com.pair.domain.task.repository.TaskRecordRepository;

/**
 * 관리자 과제 콘솔: 과제 목록, 검수 뒤 공개(HIDDEN 에서 OPEN), 다시 숨기기.
 * 목록·상태는 ERD tasks 테이블, 과제 내용(정의·파일)은 TaskRepository 가 맡는다.
 */
@Service
public class TaskConsoleService {

    /**
     * id: 과제 code. type: dev | biz (응시 화면 TaskDef.type 과 같은 값).
     * track: DEV/BIZ. generated: AI 가 만든 과제(_gen/report.json 있음). checksOk: 그 확인 검사가 모두 통과했는지(report 가 없으면 null).
     * usage: SELF 자가진단 / LIVE 기업 실전(회차에 거는 과제). parentId: 실전 복사본이면 원본 code
     */
    public record TaskSummary(String id, String type, String title, int minutes, TaskRecord.Usage usage, String parentId,
            TaskRecord.Status status, Instant publishedAt, Instant updatedAt, String track, String difficulty, String category,
            String summary, Instant createdAt, boolean generated, Boolean checksOk) {}

    private final TaskRecordRepository records;
    private final TaskCatalog catalog;
    private final Clock clock;
    private final TaskFileRecordRepository files;
    private final ObjectMapper json;

    public TaskConsoleService(TaskRecordRepository records, TaskCatalog catalog, Clock clock, TaskFileRecordRepository files,
            ObjectMapper json) {
        this.files = files;
        this.json = json;
        this.records = records;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskSummary> list() {
        List<TaskRecord> all = records.findAllByOrderByIdAsc();
        var codes = catalog.codes(all.stream().map(TaskRecord::getParentTaskId).filter(java.util.Objects::nonNull).toList());
        Map<Long, Boolean> reports = new HashMap<>(); // 과제 id → checksOk. 키가 있으면 AI 생성 과제(값은 null 일 수 있음)
        for (TaskFileRecord f : files.findByPath(GeneratedTaskWriter.REPORT_PATH)) reports.put(f.getTaskId(), checksOk(f.getContent()));
        return all.stream().map(t -> summary(t, t.getParentTaskId() == null ? null : codes.get(t.getParentTaskId()), reports)).toList();
    }

    /** 검수 전(HIDDEN)인 원본 과제만 용도를 바꿀 수 있다. 공개 뒤에는 응시 기록이 용도에 묶인다 */
    @Transactional
    public TaskSummary changeUsage(String code, TaskRecord.Usage usage) {
        TaskRecord t = catalog.require(code); // 없는 과제면 404
        if (t.getParentTaskId() != null) throw new ConflictException("실전 복사본은 용도를 바꿀 수 없습니다.");
        if (t.getStatus() != TaskRecord.Status.HIDDEN) {
            throw new ConflictException("공개한 과제는 용도를 바꿀 수 없습니다. 복제해서 새로 만드세요.");
        }
        t.changeUsage(usage, clock.instant());
        return summary(t);
    }

    private Boolean checksOk(String content) {
        try {
            JsonNode checks = json.readTree(content).get("checks");
            if (checks == null || !checks.isArray() || checks.isEmpty()) return null;
            for (JsonNode c : checks) if (!c.path("ok").asBoolean(false)) return false;
            return true;
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public TaskSummary get(String code) {
        return summary(catalog.require(code));
    }

    @Transactional
    public TaskSummary changeStatus(String code, TaskRecord.Status status) {
        TaskRecord t = catalog.require(code); // 없는 과제면 404
        t.changeStatus(status, clock.instant());
        return summary(t);
    }

    /** 회차에 걸 수 있는 과제: 기업 실전(LIVE)용이고 공개(OPEN)여야 한다. 아니면 IllegalArgumentException */
    @Transactional(readOnly = true)
    public List<TaskRecord> requireOpenLive(List<String> codes) {
        List<TaskRecord> rows = catalog.requireAll(codes); // 없는 과제면 404
        for (TaskRecord t : rows) {
            if (t.getUsage() != TaskRecord.Usage.LIVE) {
                throw new IllegalArgumentException("기업 실전용(LIVE) 과제만 회차에 넣을 수 있습니다: " + t.getCode());
            }
            if (t.getStatus() != TaskRecord.Status.OPEN) {
                throw new IllegalArgumentException("공개(OPEN)하지 않은 과제는 회차에 넣을 수 없습니다: " + t.getCode());
            }
        }
        return rows;
    }

    private TaskSummary summary(TaskRecord t) {
        String parent = t.getParentTaskId() == null ? null : catalog.require(t.getParentTaskId()).getCode();
        Map<Long, Boolean> reports = new HashMap<>();
        files.findByTaskIdAndPath(t.getId(), GeneratedTaskWriter.REPORT_PATH).ifPresent(f -> reports.put(t.getId(), checksOk(f.getContent())));
        return summary(t, parent, reports);
    }

    private static TaskSummary summary(TaskRecord t, String parentCode, Map<Long, Boolean> reports) {
        return new TaskSummary(t.getCode(), t.getTrack().toLowerCase(), t.getTitle(), t.getTimeLimitMin(), t.getUsage(), parentCode,
                t.getStatus(), t.getPublishedAt(), t.getUpdatedAt(), t.getTrack(), t.getDifficulty(), t.getCategory(), t.getSummary(),
                t.getCreatedAt(), reports.containsKey(t.getId()), reports.get(t.getId()));
    }
}
