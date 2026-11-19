package com.pair.taskconsole;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.task.TaskCatalog;
import com.pair.task.TaskDef;
import com.pair.task.TaskRecord;
import com.pair.taskconsole.TaskConsoleService.TaskSummary;
import com.pair.web.auth.CurrentUser;
import com.pair.web.auth.ForbiddenException;

/**
 * 관리자 과제 콘솔 API. 공개·숨기기는 관리자(ADMIN)만, 목록은 회차를 만드는 기업 매니저도 본다(공개 과제만).
 * 응시자로 보기는 기존 GET /api/tasks/{id} 와 자가진단 응시 화면을 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/console/tasks")
public class TaskConsoleController {

    public record TaskDetail(TaskSummary summary, TaskDef definition) {}

    private final TaskConsoleService console;
    private final TaskCatalog tasks;

    public TaskConsoleController(TaskConsoleService console, TaskCatalog tasks) {
        this.console = console;
        this.tasks = tasks;
    }

    /** 관리자: 모든 과제. 매니저: 공개한 실전(LIVE) 과제만 (회차 만들 때 고르는 목록) */
    @GetMapping
    public List<TaskSummary> list(CurrentUser user) {
        if (user.isAdmin()) return console.list();
        if (user.role() != CurrentUser.Role.MANAGER) throw new ForbiddenException("과제 목록 권한이 없습니다.");
        return console.list().stream()
                .filter(t -> t.status() == TaskRecord.Status.OPEN && t.usage() == TaskRecord.Usage.LIVE).toList();
    }

    @GetMapping("/{taskId}")
    public TaskDetail detail(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return new TaskDetail(console.get(taskId), tasks.definition(taskId));
    }

    @PostMapping("/{taskId}/publish")
    public TaskSummary publish(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return console.changeStatus(taskId, TaskRecord.Status.OPEN);
    }

    @PostMapping("/{taskId}/hide")
    public TaskSummary hide(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return console.changeStatus(taskId, TaskRecord.Status.HIDDEN);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new ForbiddenException("과제 검수·공개는 관리자만 할 수 있습니다.");
    }
}
