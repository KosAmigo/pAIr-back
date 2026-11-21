package com.pair.web;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.attempt.Attempt;
import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptRecordRepository;
import com.pair.attempt.AttemptService;
import com.pair.attempt.AttemptStore;
import com.pair.attempt.ChatTurn;
import com.pair.run.CodeRunner;
import com.pair.task.TaskCatalog;
import com.pair.task.TaskDef;
import com.pair.web.auth.CurrentUser;
import com.pair.task.TaskRepository;

import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TaskRepository tasks;
    private final AttemptService attempts;
    private final AttemptStore store;
    private final com.pair.config.PairProperties props;
    private final AttemptRecordRepository records;
    private final TaskCatalog catalog;
    private final com.pair.attempt.CheckpointService checkpoints;
    private final com.pair.attempt.RollbackService rollbacks;
    private final com.pair.attempt.ReplayTreeService replayTree;
    private final com.pair.question.QuestionService questions;
    private final com.pair.attempt.ReplayCompareService replayCompare;

    public ApiController(TaskRepository tasks, AttemptService attempts, AttemptStore store, com.pair.config.PairProperties props,
            AttemptRecordRepository records, TaskCatalog catalog, com.pair.attempt.CheckpointService checkpoints,
            com.pair.attempt.RollbackService rollbacks, com.pair.attempt.ReplayTreeService replayTree,
            com.pair.question.QuestionService questions, com.pair.attempt.ReplayCompareService replayCompare) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.store = store;
        this.props = props;
        this.records = records;
        this.catalog = catalog;
        this.checkpoints = checkpoints;
        this.rollbacks = rollbacks;
        this.replayTree = replayTree;
        this.questions = questions;
        this.replayCompare = replayCompare;
    }

    public record CheckpointLabelRequest(String label) {}

    public record StartRequest(String taskId) {}

    public record SaveRequest(String content) {}

    public record ChatRequest(String message) {}

    public record FollowUpRequest(List<String> answers) {}

    public record TurnView(int turn, String role, String time, String text, List<AttemptService.EditView> edits) {}

    public record EditRequest(int turn, int index, String action) {}

    /** 자가진단 과제 목록 (응시자용). track=dev|biz 로 직군을 거를 수 있다 */
    @GetMapping("/tasks")
    public List<TaskCatalog.Summary> taskList(@RequestParam(required = false) String track) {
        return catalog.selfTasks(track);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskDef task(@PathVariable String taskId) {
        return catalog.publicSelfDefinition(taskId);
    }

    @PostMapping("/attempts")
    public Map<String, Object> start(CurrentUser user, @RequestBody StartRequest req) {
        Attempt a = attempts.startSelf(req.taskId(), user.userId());
        return Map.of("attemptId", a.id());
    }

    /** 내 기록: 내 자가진단 응시 목록 (DB attempts, 최근 순). 채점이 끝난 응시는 항목 점수를 함께 준다. */
    @GetMapping("/attempts")
    public List<Map<String, Object>> list(CurrentUser user) {
        List<AttemptRecord> rows = records.findByUserIdAndModeOrderByStartedAtDesc(user.userId(), AttemptRecord.Mode.SELF);
        Map<Long, String> codes = catalog.codes(rows.stream().map(AttemptRecord::getTaskId).distinct().toList());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (AttemptRecord r : rows) {
            String id = String.valueOf(r.getId());
            if (r.getStatus() == Attempt.Status.SOLVING && isEmpty(id)) continue; // 들어갔다 아무것도 안 하고 나간 기록은 보여 주지 않는다
            TaskDef task = catalog.definition(codes.get(r.getTaskId()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("taskId", codes.get(r.getTaskId()));
            m.put("taskTitle", task.title());
            m.put("taskType", task.type());
            m.put("startedAt", r.getStartedAt());
            m.put("status", r.getStatus());
            if (r.getStatus() == Attempt.Status.DONE) {
                JsonNode rep = readReportSafely(id);
                if (rep != null) {
                    // version 2(감점식)만 총점이 있다. 이전 형식은 다시 채점해야 한다.
                    m.put("total", rep.path("version").asInt() >= 2 ? rep.path("total").asInt() : null);
                    m.put("legacyReport", rep.path("version").asInt() < 2);
                    for (JsonNode it : rep.path("items")) {
                        JsonNode sc = it.path("score");
                        m.put(it.path("key").asText(), sc.isNumber() ? sc.asDouble() : null);
                    }
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 리포트 파일이 없거나 깨져도 목록은 보여 준다 */
    private JsonNode readReportSafely(String id) {
        try {
            return store.readReport(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 빈 응시: 풀이 중이고, AI 질문도, 파일 저장, 빌드, 실행도 없는 기록. 파일이 없으면 빈 것으로 본다 */
    private boolean isEmpty(String id) {
        try {
            if (!store.turns(id).isEmpty()) return false;
            return store.events(id).stream().allMatch(e -> "start".equals(e.path("type").asText()));
        } catch (RuntimeException e) {
            return true;
        }
    }

    @GetMapping("/attempts/{id}")
    public Map<String, Object> attempt(@PathVariable String id) {
        Attempt a = store.load(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", a);
        m.put("task", tasks.get(a.taskId()));
        m.put("files", store.readFiles(id).keySet());
        var edits = attempts.edits(id);
        m.put("turns", store.turns(id).stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()))).toList());
        m.put("chatMaxChars", props.assistant().maxInputChars());
        m.put("elapsedSeconds", Duration.between(a.startedAt(), a.submittedAt() != null ? a.submittedAt() : Instant.now()).toSeconds());
        return m;
    }

    @GetMapping("/attempts/{id}/file")
    public Map<String, String> file(@PathVariable String id, @RequestParam String path) {
        String content = store.readFiles(id).get(path);
        if (content == null) throw new java.util.NoSuchElementException("없는 파일입니다: " + path);
        return Map.of("path", path, "content", content);
    }

    @PutMapping("/attempts/{id}/file")
    public Map<String, Object> save(@PathVariable String id, @RequestParam String path, @RequestBody SaveRequest req) {
        attempts.saveFile(id, path, req.content() == null ? "" : req.content());
        return Map.of("saved", true);
    }

    @PostMapping("/attempts/{id}/build")
    public CodeRunner.BuildResult build(@PathVariable String id) {
        return attempts.build(id);
    }

    @PostMapping("/attempts/{id}/run")
    public CodeRunner.RunResult run(@PathVariable String id) {
        return attempts.run(id);
    }

    @PostMapping("/attempts/{id}/chat")
    public List<TurnView> chat(@PathVariable String id, @RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) throw new IllegalArgumentException("메시지가 비어 있습니다.");
        Attempt a = store.load(id);
        List<ChatTurn> added = attempts.chat(id, req.message());
        var edits = attempts.edits(id);
        return added.stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()))).toList();
    }

    private final java.util.concurrent.ExecutorService streams = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 채팅 스트리밍 (Server-Sent Events). 답변 글자가 오는 대로 "delta" 이벤트로 보내고,
     * 다 받아 기록에 저장한 뒤 "done" 이벤트로 새 턴 2개(질문, 답변)를 보낸다. 실패하면 "error" 이벤트.
     */
    @PostMapping(value = "/attempts/{id}/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chatStream(@PathVariable String id, @RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) throw new IllegalArgumentException("메시지가 비어 있습니다.");
        Attempt a = store.load(id);
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(6 * 60 * 1000L);
        streams.submit(() -> {
            try {
                List<ChatTurn> added = attempts.chat(id, req.message(), delta -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("delta").data(Map.of("t", delta)));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e); // 응시자가 화면을 닫으면 받기를 멈춘다
                    }
                });
                var edits = attempts.edits(id);
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("done")
                        .data(added.stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()))).toList()));
                emitter.complete();
            } catch (Exception e) {
                try {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("error").data(Map.of("message", msg)));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /** AI 수정 제안 적용(apply) 또는 거절(reject). */
    @PostMapping("/attempts/{id}/edits")
    public AttemptService.EditDecision decideEdit(@PathVariable String id, @RequestBody EditRequest req) {
        if (!"apply".equals(req.action()) && !"reject".equals(req.action())) throw new IllegalArgumentException("action 은 apply 또는 reject 입니다.");
        return attempts.decideEdit(id, req.turn(), req.index(), "apply".equals(req.action()));
    }

    /** 복기 트리: 이 응시의 체크포인트(질문마다 하나) 목록 */
    @GetMapping("/attempts/{id}/checkpoints")
    public List<com.pair.attempt.CheckpointService.View> checkpoints(@PathVariable String id) {
        return checkpoints.list(id);
    }

    /** 체크포인트 이름 붙이기(복기 트리에서). 빈 값이면 이름을 지운다. 내 체크포인트만 */
    @PatchMapping("/checkpoints/{checkpointId}")
    public com.pair.attempt.CheckpointService.View renameCheckpoint(CurrentUser user, @PathVariable Long checkpointId,
            @RequestBody CheckpointLabelRequest req) {
        return checkpoints.rename(user.userId(), checkpointId, req.label());
    }

    /** 복기 트리: 이 응시가 속한 트리의 모든 응시(부모, 갈라진 질문 시점 포함). 내 응시만 */
    @GetMapping("/attempts/{id}/tree")
    public List<com.pair.attempt.ReplayTreeService.Node> tree(CurrentUser user, @PathVariable String id) {
        return replayTree.tree(user.userId(), id);
    }

    /** 복기 비교: 두 풀이의 감점 근거를 항목·체크별로 나란히 준다(채점이 끝난 내 풀이만) */
    @GetMapping("/attempts/{id}/compare")
    public com.pair.attempt.ReplayCompareService.Result compare(CurrentUser user, @PathVariable String id, @RequestParam("with") String with) {
        return replayCompare.compare(user.userId(), id, with);
    }

    /** 복기 비교: 과제 원본과 두 풀이의 최종 코드(코드 변경 비교용). 원본·A·B 가 모두 같은 파일은 뺀다. 내 자가진단 응시만 */
    @GetMapping("/attempts/{id}/compare/files")
    public List<com.pair.attempt.ReplayCompareService.FileDiff> compareFiles(CurrentUser user, @PathVariable String id,
            @RequestParam("with") String with) {
        return replayCompare.files(user.userId(), id, with);
    }

    /** 롤백: 체크포인트에서 갈라진 새 응시를 만든다 */
    @PostMapping("/checkpoints/{checkpointId}/rollback")
    public com.pair.attempt.RollbackService.Result rollback(CurrentUser user, @PathVariable Long checkpointId) {
        return rollbacks.rollback(user.userId(), checkpointId);
    }

    @PostMapping("/attempts/{id}/submit")
    public Attempt submit(@PathVariable String id) {
        return attempts.submit(id);
    }

    @PostMapping("/attempts/{id}/followups")
    public Attempt followUps(@PathVariable String id, @RequestBody FollowUpRequest req) {
        return attempts.followUps(id, req.answers());
    }

    @PostMapping("/attempts/{id}/rescore")
    public Attempt rescore(@PathVariable String id) {
        return attempts.rescore(id);
    }

    @GetMapping("/attempts/{id}/report")
    public Map<String, Object> report(@PathVariable String id) {
        Attempt a = store.load(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", a.status());
        m.put("error", a.error());
        JsonNode report = a.status() == Attempt.Status.DONE ? store.readReport(id) : null;
        m.put("report", report);
        // 문항(객관식·단답형) 점수는 6개 역량 점수와 따로 보여 준다. 문항이 없으면 null. 정답은 제출 뒤 리포트에서만 나간다.
        m.put("questions", a.status() == Attempt.Status.DONE ? questions.report(Long.parseLong(id)) : null);
        return m;
    }

    /** 풀이 화면의 문항(객관식·단답형)과 저장한 답. 정답 정보는 내보내지 않는다 */
    @GetMapping("/attempts/{id}/questions")
    public List<com.pair.question.QuestionService.QuestionView> questions(@PathVariable String id) {
        return questions.list(Long.parseLong(id));
    }

    /** 문항 답 저장(덮어쓰기). 빈 답은 답 지우기. 풀이 중에만 */
    @PutMapping("/attempts/{id}/questions/{questionId}/answer")
    public org.springframework.http.ResponseEntity<String> answer(@PathVariable String id, @PathVariable Long questionId,
            @RequestBody com.pair.question.QuestionService.AnswerRequest req) throws com.fasterxml.jackson.core.JsonProcessingException {
        // 답 지우기(빈 답)는 JSON null 을 돌려준다
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(questions.answer(Long.parseLong(id), questionId, req));
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }

    private static TurnView view(Attempt a, ChatTurn t, List<AttemptService.EditView> edits) {
        long s = Math.max(0, Duration.between(a.startedAt(), t.at()).toSeconds());
        return new TurnView(t.turn(), t.role(), String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60), t.text(), edits);
    }
}
