package com.pair.taskconsole;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.task.TaskCatalog;
import com.pair.task.TaskRecord;
import com.pair.task.TaskRecordRepository;

/**
 * 관리자 과제 콘솔: 과제 목록, 검수 뒤 공개(HIDDEN 에서 OPEN), 다시 숨기기.
 * 목록·상태는 ERD tasks 테이블, 과제 내용(정의·파일)은 TaskRepository 가 맡는다.
 */
@Service
public class TaskConsoleService {

    /**
     * id: 과제 code. type: dev | biz (응시 화면 TaskDef.type 과 같은 값).
     * usage: SELF 자가진단 / LIVE 기업 실전(회차에 거는 과제). parentId: 실전 복사본이면 원본 code
     */
    public record TaskSummary(String id, String type, String title, int minutes, TaskRecord.Usage usage, String parentId,
            TaskRecord.Status status, Instant publishedAt, Instant updatedAt) {}

    private final TaskRecordRepository records;
    private final TaskCatalog catalog;
    private final Clock clock;

    public TaskConsoleService(TaskRecordRepository records, TaskCatalog catalog, Clock clock) {
        this.records = records;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskSummary> list() {
        List<TaskRecord> all = records.findAllByOrderByIdAsc();
        var codes = catalog.codes(all.stream().map(TaskRecord::getParentTaskId).filter(java.util.Objects::nonNull).toList());
        return all.stream().map(t -> summary(t, t.getParentTaskId() == null ? null : codes.get(t.getParentTaskId()))).toList();
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
        return summary(t, parent);
    }

    private static TaskSummary summary(TaskRecord t, String parentCode) {
        return new TaskSummary(t.getCode(), t.getTrack().toLowerCase(), t.getTitle(), t.getTimeLimitMin(), t.getUsage(), parentCode,
                t.getStatus(), t.getPublishedAt(), t.getUpdatedAt());
    }
}
