package com.pair.taskconsole;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.task.TaskDef;
import com.pair.task.TaskRepository;

/**
 * 관리자 과제 콘솔: 과제 목록, 검수 뒤 공개(HIDDEN 에서 OPEN), 다시 숨기기.
 * 과제 내용(정의·파일)은 TaskRepository(팀원 2·3)가 맡고, 여기는 공개 상태만 다룬다.
 */
@Service
public class TaskConsoleService {

    public record TaskSummary(String id, String type, String title, int minutes, TaskPublication.Status status,
            Instant publishedAt, Instant updatedAt, Long updatedBy) {}

    private final TaskRepository tasks;
    private final TaskPublicationRepository publications;
    private final Clock clock;

    public TaskConsoleService(TaskRepository tasks, TaskPublicationRepository publications, Clock clock) {
        this.tasks = tasks;
        this.publications = publications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskSummary> list() {
        List<TaskDef> all = tasks.all();
        Map<String, TaskPublication> pubs = byId(all.stream().map(TaskDef::id).toList());
        return all.stream().map(t -> summary(t, pubs.get(t.id()))).toList();
    }

    @Transactional(readOnly = true)
    public TaskSummary get(String taskId) {
        TaskDef t = tasks.get(taskId);
        return summary(t, publications.findById(taskId).orElse(null));
    }

    @Transactional
    public TaskSummary changeStatus(String taskId, TaskPublication.Status status, Long adminId) {
        TaskDef t = tasks.get(taskId); // 없는 과제면 404
        TaskPublication p = publications.findById(taskId).orElseGet(() -> new TaskPublication(taskId));
        p.change(status, adminId, clock.instant());
        return summary(t, publications.save(p));
    }

    /** 회차에 걸 수 있는지: 모두 OPEN 이어야 한다. 아니면 IllegalArgumentException */
    @Transactional(readOnly = true)
    public void requireOpen(Collection<String> taskIds) {
        Map<String, TaskPublication> pubs = byId(taskIds);
        for (String id : taskIds) {
            TaskPublication p = pubs.get(id);
            if (p == null || p.getStatus() != TaskPublication.Status.OPEN) {
                throw new IllegalArgumentException("공개(OPEN)하지 않은 과제는 회차에 넣을 수 없습니다: " + id);
            }
        }
    }

    private Map<String, TaskPublication> byId(Collection<String> ids) {
        return publications.findByTaskIdIn(ids).stream()
                .collect(Collectors.toMap(TaskPublication::getTaskId, Function.identity()));
    }

    private static TaskSummary summary(TaskDef t, TaskPublication p) {
        if (p == null) return new TaskSummary(t.id(), t.type(), t.title(), t.minutes(), TaskPublication.Status.HIDDEN, null, null, null);
        return new TaskSummary(t.id(), t.type(), t.title(), t.minutes(), p.getStatus(), p.getPublishedAt(), p.getUpdatedAt(),
                p.getUpdatedBy());
    }
}
