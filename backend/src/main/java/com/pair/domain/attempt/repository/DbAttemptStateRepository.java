package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.attempt.dto.ChatTurnStateDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.attempt.entity.AttemptEvent;
import com.pair.domain.attempt.entity.AttemptFile;
import com.pair.domain.attempt.entity.AttemptReport;
import com.pair.domain.attempt.entity.ChatTurn;
import com.pair.domain.scoring.repository.ScoringReportRepository;
import com.pair.domain.task.service.TaskCatalogService;

/**
 * 실제 응시 기록을 DB 에 읽고 쓴다.
 * 상태 attempts, 작업 파일 attempt_files, 대화 chat_turns, 작업 기록 attempt_events, 리포트 attempt_reports.
 * 응시 행(attempts)은 AttemptRegistryService 가 먼저 만들고, 여기서는 그 행을 고치기만 한다.
 */
@Component
public class DbAttemptStateRepository implements AttemptStateRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final AttemptRepository records;
    private final AttemptFileRepository files;
    private final ChatTurnRepository turns;
    private final AttemptEventRepository events;
    private final AttemptReportRepository reports;
    private final TaskCatalogService catalog;
    private final ObjectMapper json;
    private final ScoringReportRepository scoringReports;

    /** DB task_id → 과제 내용 code. 과제 code 는 바뀌지 않아 한 번 읽으면 둔다 */
    private final Map<Long, String> taskCodes = new ConcurrentHashMap<>();

    public DbAttemptStateRepository(AttemptRepository records, AttemptFileRepository files, ChatTurnRepository turns,
                          AttemptEventRepository events, AttemptReportRepository reports, TaskCatalogService catalog,
                          ObjectMapper json, ScoringReportRepository scoringReports) {
        this.records = records;
        this.files = files;
        this.turns = turns;
        this.events = events;
        this.reports = reports;
        this.catalog = catalog;
        this.json = json;
        this.scoringReports = scoringReports;
    }

    // ── 응시 상태 ─────────────────────

    @Override
    @Transactional
    public void create(AttemptStateDto a, Map<String, String> initial) {
        Long id = key(a.id());
        Instant now = Instant.now();
        for (var e : new TreeMap<>(initial).entrySet()) {
            files.save(new AttemptFile(id, e.getKey(), e.getValue(), now));
        }
        save(a);
    }

    @Override
    @Transactional
    public void save(AttemptStateDto a) {
        Attempt r = record(a.id());
        r.apply(a, toJson(a.followUpQuestions()), toJson(a.followUpAnswers()));
        records.save(r);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptStateDto load(String id) {
        Attempt r = record(id);
        List<String> answers = readStrings(r.getFollowupAnswers());
        return new AttemptStateDto(id, taskCode(r.getTaskId()), r.getProvider(), r.getAssistantModel(), r.getStartedAt(),
                r.getSubmittedAt(), r.getFollowupsDoneAt(), r.getFirstValidAt(), r.getStatus(),
                answers == null ? List.of() : answers, r.getError(), readStrings(r.getFollowupQuestions()));
    }

    /** 후속 질문까지 마친(채점할 수 있는) 응시, 최근 순 */
    @Override
    @Transactional(readOnly = true)
    public List<String> ids() {
        return records.findFinishedIds().stream().map(String::valueOf).toList();
    }

    @Override
    public String location(String id) {
        return "DB attempts.attempt_id=" + id;
    }

    // ── 작업 파일 ─────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> readFiles(String id) {
        Map<String, String> out = new TreeMap<>();
        for (AttemptFile f : files.findByAttemptIdOrderByPathAsc(key(id))) out.put(f.getPath(), f.getContent());
        return out;
    }

    @Override
    @Transactional
    public void writeWorkspaceFile(String id, String path, String content) {
        AttemptFile f = files.findByAttemptIdAndPath(key(id), path)
                .orElseThrow(() -> new IllegalArgumentException("수정할 수 없는 파일입니다: " + path));
        f.update(content, Instant.now());
        files.save(f);
    }

    @Override
    @Transactional
    public void createWorkspaceFile(String id, String path) {
        if (!AttemptStateRepository.creatable(path)) throw new IllegalArgumentException("이 경로에는 파일을 만들 수 없습니다: " + path);
        Long attemptId = key(id);
        if (files.existsByAttemptIdAndPath(attemptId, path)) return;
        files.save(new AttemptFile(attemptId, path, "", Instant.now()));
    }

    // ── 대화, 작업 기록 ─────────────────

    @Override
    @Transactional
    public void appendTurn(String id, ChatTurnStateDto t) {
        turns.save(new ChatTurn(key(id), t.turn(), t.role().toUpperCase(), t.text() == null ? "" : t.text(),
                t.model(), toInt(t.inputTokens()), toInt(t.outputTokens()), t.at() == null ? Instant.now() : t.at(),
                t.provider(), t.request() == null ? null : toJson(t.request()), toInt(t.latencyMs())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatTurnStateDto> turns(String id) {
        List<ChatTurnStateDto> out = new ArrayList<>();
        for (ChatTurn r : turns.findByAttemptIdOrderByTurnNoAsc(key(id))) {
            out.add(new ChatTurnStateDto(r.getTurnNo(), r.getRole().toLowerCase(), r.getCreatedAt(), r.getContent(),
                    r.getProvider(), r.getModel(), readRequest(r.getRequest()),
                    toLong(r.getInputTokens()), toLong(r.getOutputTokens()), toLong(r.getLatencyMs())));
        }
        return out;
    }

    @Override
    @Transactional
    public void appendEvent(String id, String type, Map<String, Object> data) {
        events.save(new AttemptEvent(key(id), dbType(type), toJson(new LinkedHashMap<>(data)), Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<JsonNode> events(String id) {
        List<JsonNode> out = new ArrayList<>();
        for (AttemptEvent e : events.findByAttemptIdOrderByIdAsc(key(id))) {
            ObjectNode n = e.getPayload() == null ? json.createObjectNode() : (ObjectNode) readTree(e.getPayload());
            n.put("type", appType(e.getType()));
            n.put("at", e.getCreatedAt().toString());
            out.add(n);
        }
        return out;
    }

    /** 대화·작업 기록 내용은 읽지 않고, 있는 응시 ID 만 한 번씩 묻는다 */
    @Override
    @Transactional(readOnly = true)
    public Set<String> activeIds(List<String> ids) {
        if (ids.isEmpty()) return Set.of();
        List<Long> keys = ids.stream().map(DbAttemptStateRepository::key).toList();
        Set<String> out = new HashSet<>();
        turns.findAttemptIdsWithTurns(keys).forEach(id -> out.add(String.valueOf(id)));
        events.findAttemptIdsWithEventsOtherThan(keys, dbType("start")).forEach(id -> out.add(String.valueOf(id)));
        return out;
    }

    // ── 리포트 ─────────────────────────

    @Override
    @Transactional
    public void writeReport(String id, Object report) {
        Long attemptId = key(id);
        String body = toJson(report);
        Instant now = Instant.now();
        AttemptReport r = reports.findById(attemptId).orElse(null);
        if (r == null) r = new AttemptReport(attemptId, body, now);
        else r.update(body, now);
        reports.save(r);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode readReport(String id) {
        Long attemptId = key(id);
        JsonNode stored = reports.findById(attemptId).map(r -> readTree(r.getReport())).orElse(null);
        return scoringReports.overlay(attemptId, stored);
    }

    /** 응시마다 리포트를 만들지 않는다: 점수는 채점 표에서 한꺼번에, 채점 표에 없는 응시만 저장된 JSON 을 한 번에 읽는다 */
    @Override
    @Transactional(readOnly = true)
    public Map<String, JsonNode> readScores(List<String> ids) {
        List<Long> keys = ids.stream().map(DbAttemptStateRepository::key).toList();
        Map<String, JsonNode> out = new HashMap<>();
        scoringReports.scores(keys).forEach((id, report) -> out.put(String.valueOf(id), report));
        List<Long> rest = keys.stream().filter(k -> !out.containsKey(String.valueOf(k))).toList();
        for (AttemptReport r : reports.findAllById(rest)) {
            try {
                out.put(String.valueOf(r.getAttemptId()), readTree(r.getReport()));
            } catch (IllegalStateException e) {
                // 깨진 리포트는 이 응시만 뺀다
            }
        }
        return out;
    }

    // ── 내부 ───────────────────────────

    /** 응용의 이벤트 이름(aiEdit) ↔ DB type(AI_EDIT) */
    static String dbType(String type) {
        return type.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    static String appType(String dbType) {
        String[] parts = dbType.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private Attempt record(String id) {
        return records.findById(key(id)).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + id));
    }

    private static Long key(String id) {
        Long k = AttemptRegistryService.parse(id);
        if (k == null) throw new NoSuchElementException("없는 응시입니다: " + id);
        return k;
    }

    private String taskCode(Long taskId) {
        return taskCodes.computeIfAbsent(taskId, k -> catalog.definitionCode(catalog.require(k)));
    }

    private String toJson(Object v) {
        if (v == null) return null;
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 으로 바꾸지 못했습니다", e);
        }
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 JSON 을 읽지 못했습니다", e);
        }
    }

    private List<String> readStrings(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 JSON 을 읽지 못했습니다", e);
        }
    }

    private ChatTurnStateDto.AssistantRequest readRequest(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, ChatTurnStateDto.AssistantRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 AI 요청을 읽지 못했습니다", e);
        }
    }

    private static Integer toInt(Long v) {
        return v == null ? null : Math.toIntExact(v);
    }

    private static Long toLong(Integer v) {
        return v == null ? null : v.longValue();
    }
}
