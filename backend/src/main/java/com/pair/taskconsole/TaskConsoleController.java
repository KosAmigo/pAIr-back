package com.pair.taskconsole;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.task.TaskCatalog;
import com.pair.task.TaskDef;
import com.pair.task.TaskMaterialService;
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
    private final TaskMaterialService materials;
    private final TaskReviewService review;

    public TaskConsoleController(TaskConsoleService console, TaskCatalog tasks, TaskMaterialService materials,
            TaskReviewService review) {
        this.console = console;
        this.tasks = tasks;
        this.materials = materials;
        this.review = review;
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

    public record UsageRequest(TaskRecord.Usage usage) {}

    /** 검수 전(HIDDEN) 원본 과제의 용도(SELF/LIVE) 바꾸기. 공개한 과제·실전 복사본은 409 */
    @PatchMapping("/{taskId}/usage")
    public TaskSummary changeUsage(CurrentUser user, @PathVariable String taskId, @RequestBody UsageRequest req) {
        requireAdmin(user);
        if (req == null || req.usage() == null) throw new IllegalArgumentException("usage 는 SELF 또는 LIVE 여야 합니다.");
        return console.changeUsage(taskId, req.usage());
    }

    /** 검수용: 과제의 모든 파일(숨은 파일 포함). PDF 는 content 가 null (기존 /materials 로 본다) */
    @GetMapping("/{taskId}/files")
    public List<TaskReviewService.FileView> files(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return review.files(taskId);
    }

    /** 검수용: 과제 문항과 정답·근거 (후속 질문 제외) */
    @GetMapping("/{taskId}/questions")
    public List<TaskReviewService.QuestionView> questions(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return review.questions(taskId);
    }

    /** 검수용 PDF 자료 목록 (공개 전 과제 포함, 숨은 파일 제외) */
    @GetMapping("/{taskId}/materials")
    public List<TaskMaterialService.Material> materials(CurrentUser user, @PathVariable String taskId) {
        requireAdmin(user);
        return materials.listForTask(taskId);
    }

    @GetMapping("/{taskId}/materials/file")
    public org.springframework.http.ResponseEntity<byte[]> materialFile(CurrentUser user, @PathVariable String taskId,
            @org.springframework.web.bind.annotation.RequestParam String path) {
        requireAdmin(user);
        return com.pair.web.MaterialResponses.pdf(materials.fileForTask(taskId, path));
    }

    @GetMapping("/{taskId}/materials/pages")
    public List<TaskMaterialService.PageText> materialPages(CurrentUser user, @PathVariable String taskId,
            @org.springframework.web.bind.annotation.RequestParam String path,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String pages) {
        requireAdmin(user);
        return materials.pagesForTask(taskId, path, pages);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new ForbiddenException("과제 검수·공개는 관리자만 할 수 있습니다.");
    }
}
