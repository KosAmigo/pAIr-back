package com.pair.domain.attempt.controller;

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

import com.pair.domain.attempt.dto.AttemptDtos.CheckpointLabelRequest;
import com.pair.domain.attempt.dto.AttemptDtos.StartRequest;
import com.pair.domain.attempt.dto.AttemptDtos.SaveRequest;
import com.pair.domain.attempt.dto.AttemptDtos.ChatRequest;
import com.pair.domain.attempt.dto.AttemptDtos.FollowUpRequest;
import com.pair.domain.attempt.dto.AttemptDtos.TurnView;
import com.pair.domain.attempt.dto.AttemptDtos.EditRequest;
import com.pair.domain.attempt.dto.AttemptDtos.DiagnosticsRequest;
import com.pair.domain.attempt.dto.AttemptDtos.CursorRequest;
import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.repository.AttemptRecordRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.repository.AttemptStore;
import com.pair.domain.attempt.service.ChatAttachmentService;
import com.pair.domain.attempt.service.ChatTurn;
import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.JavaDiagnostics;
import com.pair.domain.task.service.TaskCatalog;
import com.pair.domain.task.service.TaskDef;
import com.pair.global.security.CurrentUser;
import com.pair.domain.task.repository.TaskRepository;

import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class AttemptController {

    private final TaskRepository tasks;
    private final AttemptService attempts;
    private final AttemptStore store;
    private final com.pair.global.config.PairProperties props;
    private final AttemptRecordRepository records;
    private final TaskCatalog catalog;
    private final com.pair.domain.question.service.QuestionService questions;
    private final com.pair.domain.attempt.service.CheckpointService checkpoints;
    private final com.pair.domain.attempt.service.RollbackService rollbacks;
    private final com.pair.domain.attempt.service.ReplayTreeService replayTree;
    private final com.pair.domain.attempt.service.ReplayCompareService replayCompare;
    private final ChatAttachmentService chatAttachments;

    public AttemptController(TaskRepository tasks, AttemptService attempts, AttemptStore store, com.pair.global.config.PairProperties props,
            AttemptRecordRepository records, TaskCatalog catalog, com.pair.domain.attempt.service.CheckpointService checkpoints,
            com.pair.domain.attempt.service.RollbackService rollbacks, com.pair.domain.attempt.service.ReplayTreeService replayTree,
            com.pair.domain.question.service.QuestionService questions, com.pair.domain.attempt.service.ReplayCompareService replayCompare,
            ChatAttachmentService chatAttachments) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.store = store;
        this.props = props;
        this.records = records;
        this.catalog = catalog;
        this.questions = questions;
        this.checkpoints = checkpoints;
        this.rollbacks = rollbacks;
        this.replayTree = replayTree;
        this.replayCompare = replayCompare;
        this.chatAttachments = chatAttachments;
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
        // 같은 과제를 여러 번 푼 응시가 많아서 과제 정의는 한 번만 만든다 (응시마다 DB 에서 다시 읽지 않는다)
        Map<String, TaskDef> definitions = new java.util.HashMap<>();
        for (AttemptRecord r : rows) {
            String id = String.valueOf(r.getId());
            if (r.getStatus() == Attempt.Status.SOLVING && isEmpty(id)) continue; // 들어갔다 아무것도 안 하고 나간 기록은 보여 주지 않는다
            TaskDef task = definitions.computeIfAbsent(codes.get(r.getTaskId()), catalog::definition);
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
        var attached = chatAttachments.views(Long.parseLong(id));
        m.put("turns", store.turns(id).stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()), attached)).toList());
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

    /** 입력 중인 코드의 오류 밑줄. files 는 화면의 저장 전 내용(경로 → 내용), 서버에 저장된 나머지 파일과 합쳐 분석한다. */
    @PostMapping("/attempts/{id}/diagnostics")
    public Map<String, List<CodeRunner.CompileDiagnostic>> diagnostics(@PathVariable String id, @RequestBody DiagnosticsRequest req) {
        return Map.of("diagnostics", attempts.diagnostics(id, req.files()));
    }

    @PostMapping("/attempts/{id}/complete")
    public Map<String, List<JavaDiagnostics.Completion>> complete(@PathVariable String id, @RequestBody CursorRequest req) {
        return Map.of("items", attempts.complete(id, req.files(), req.path(), req.offset()));
    }

    /** 커서 위치 이름의 정의. 작업 파일 안에 없으면 definition 은 null. */
    @PostMapping("/attempts/{id}/definition")
    public java.util.Map<String, JavaDiagnostics.Definition> definition(@PathVariable String id, @RequestBody CursorRequest req) {
        var d = attempts.definition(id, req.files(), req.path(), req.offset());
        var out = new java.util.HashMap<String, JavaDiagnostics.Definition>();
        out.put("definition", d);
        return out;
    }

    @PostMapping("/attempts/{id}/run")
    public CodeRunner.RunResult run(@PathVariable String id) {
        return attempts.run(id);
    }

    @PostMapping("/attempts/{id}/chat")
    public List<TurnView> chat(@PathVariable String id, @RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) throw new IllegalArgumentException("메시지가 비어 있습니다.");
        Attempt a = store.load(id);
        List<ChatTurn> added = attempts.chat(id, req.message(), req.attachments(), null);
        var edits = attempts.edits(id);
        var attached = chatAttachments.views(Long.parseLong(id));
        return added.stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()), attached)).toList();
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
                List<ChatTurn> added = attempts.chat(id, req.message(), req.attachments(), delta -> {
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("delta").data(Map.of("t", delta)));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e); // 응시자가 화면을 닫으면 받기를 멈춘다
                    }
                });
                var edits = attempts.edits(id);
                var attached = chatAttachments.views(Long.parseLong(id));
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("done")
                        .data(added.stream().map(t -> view(a, t, edits.getOrDefault(t.turn(), List.of()), attached)).toList()));
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

    /** 질문에 붙였던 첨부의 원문(그때 AI 에게 실제로 보낸 글자). 응시 주인만(복기 화면에서 펼쳐 본다) */
    @GetMapping("/attempts/{id}/attachments/{attachmentId}")
    public ChatAttachmentService.Detail attachment(@PathVariable String id, @PathVariable long attachmentId) {
        return chatAttachments.detail(Long.parseLong(id), attachmentId);
    }

    /** AI 수정 제안 적용(apply) 또는 거절(reject). */
    @PostMapping("/attempts/{id}/edits")
    public AttemptService.EditDecision decideEdit(@PathVariable String id, @RequestBody EditRequest req) {
        if (!"apply".equals(req.action()) && !"reject".equals(req.action())) throw new IllegalArgumentException("action 은 apply 또는 reject 입니다.");
        return attempts.decideEdit(id, req.turn(), req.index(), "apply".equals(req.action()));
    }

    /** 복기 트리: 이 응시의 체크포인트(질문마다 하나) 목록 */
    @GetMapping("/attempts/{id}/checkpoints")
    public List<com.pair.domain.attempt.service.CheckpointService.View> checkpoints(@PathVariable String id) {
        return checkpoints.list(id);
    }

    /** 체크포인트 이름 붙이기(복기 트리에서). 빈 값이면 이름을 지운다. 내 체크포인트만 */
    @PatchMapping("/checkpoints/{checkpointId}")
    public com.pair.domain.attempt.service.CheckpointService.View renameCheckpoint(CurrentUser user, @PathVariable Long checkpointId,
            @RequestBody CheckpointLabelRequest req) {
        return checkpoints.rename(user.userId(), checkpointId, req.label());
    }

    /** 복기 트리: 이 응시가 속한 트리의 모든 응시(부모, 갈라진 질문 시점 포함). 내 응시만 */
    @GetMapping("/attempts/{id}/tree")
    public List<com.pair.domain.attempt.service.ReplayTreeService.Node> tree(CurrentUser user, @PathVariable String id) {
        return replayTree.tree(user.userId(), id);
    }

    /** 복기 비교: 두 풀이의 감점 근거를 항목·체크별로 나란히 준다(채점이 끝난 내 풀이만) */
    @GetMapping("/attempts/{id}/compare")
    public com.pair.domain.attempt.service.ReplayCompareService.Result compare(CurrentUser user, @PathVariable String id, @RequestParam("with") String with) {
        return replayCompare.compare(user.userId(), id, with);
    }

    /** 복기 비교: 과제 원본과 두 풀이의 최종 코드(코드 변경 비교용). 원본·A·B 가 모두 같은 파일은 뺀다. 내 자가진단 응시만 */
    @GetMapping("/attempts/{id}/compare/files")
    public List<com.pair.domain.attempt.service.ReplayCompareService.FileDiff> compareFiles(CurrentUser user, @PathVariable String id,
            @RequestParam("with") String with) {
        return replayCompare.files(user.userId(), id, with);
    }

    /** 롤백: 체크포인트에서 갈라진 새 응시를 만든다 */
    @PostMapping("/checkpoints/{checkpointId}/rollback")
    public com.pair.domain.attempt.service.RollbackService.Result rollback(CurrentUser user, @PathVariable Long checkpointId) {
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
        JsonNode report = a.status() == Attempt.Status.DONE ? withAttachments(store.readReport(id), Long.parseLong(id)) : null;
        m.put("report", report);
        // 문항(객관식·단답형) 점수는 6개 역량 점수와 따로 보여 준다. 문항이 없으면 null. 정답은 제출 뒤 리포트에서만 나간다.
        m.put("questions", a.status() == Attempt.Status.DONE ? questions.report(Long.parseLong(id)) : null);
        return m;
    }

    /**
     * 리포트의 대화 기록(transcript)은 채점 때 저장한 사본이라 첨부 정보가 없다. 복기 화면이 붙였던 파일을 보여 줄 수 있게
     * 질문 턴마다 첨부 목록(attachments)을 읽을 때 얹는다. 저장된 리포트는 바꾸지 않는다.
     */
    private JsonNode withAttachments(JsonNode report, long attemptId) {
        if (report == null || !report.path("transcript").isArray()) return report;
        var attached = chatAttachments.views(attemptId);
        if (attached.isEmpty()) return report;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode copy = report.deepCopy();
        for (JsonNode t : copy.get("transcript")) {
            var list = attached.get(t.path("turn").asInt(-1));
            if (list != null && t instanceof com.fasterxml.jackson.databind.node.ObjectNode o) o.set("attachments", mapper.valueToTree(list));
        }
        return copy;
    }

    private static TurnView view(Attempt a, ChatTurn t, List<AttemptService.EditView> edits,
            Map<Integer, List<ChatAttachmentService.View>> attached) {
        long s = Math.max(0, Duration.between(a.startedAt(), t.at()).toSeconds());
        return new TurnView(t.turn(), t.role(), String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60), t.text(), edits,
                attached.getOrDefault(t.turn(), List.of()));
    }
}
