package com.pair.domain.taskgen.repository;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.service.BizCheckService;
import com.pair.domain.taskgen.service.PdfService;
import com.pair.domain.taskgen.service.TaskGeneratorService;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.infra.storage.TaskFileStorage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import java.sql.SQLException;
import java.util.function.IntFunction;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.entity.DocumentPage;
import com.pair.domain.task.repository.DocumentPageRepository;
import com.pair.domain.task.entity.QuestionChoice;
import com.pair.domain.task.repository.QuestionChoiceRepository;
import com.pair.domain.task.entity.QuestionSource;
import com.pair.domain.task.repository.QuestionSourceRepository;
import com.pair.domain.task.entity.ShortAnswerKey;
import com.pair.domain.task.repository.ShortAnswerKeyRepository;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;

/**
 * 생성한 과제를 DB 에 한 트랜잭션으로 저장한다 (tasks, task_files, task_questions).
 * 중간에 실패하면 과제가 반쯤 남지 않는다. 저장된 과제는 검수 전(HIDDEN)이다.
 *
 * 파일 경로 규칙은 order-deadlock 시드(V6)와 같다.
 * - 시작 코드, 공개 테스트: 그대로(src/…, test/…), VISIBLE
 * - 숨은 테스트: 앞의 test/ 를 뗀 경로(shop/HiddenXTest.java), HIDDEN. 채점이 hidden/ 을 붙여 컴파일한다
 * - 정답 설명: answer.md, HIDDEN
 * - 확인 결과 _gen/report.json, 정답 코드 _gen/solution/…, HIDDEN. 정답 코드는 채점 때 .java 로 컴파일되면 클래스가
 *   겹치므로 끝에 .txt 를 붙여 둔다
 */
@Component
public class GeneratedTaskRepository {

    public static final String REPORT_PATH = "_gen/report.json";
    public static final String SOLUTION_DIR = "_gen/solution/";

    /** DEV 과제 공통 후속 질문 (order-deadlock 시드와 같은 문구) */
    public static final List<String> DEV_FOLLOW_UPS = List.of(
            "AI 답변 중 그대로 믿지 않고 직접 확인한 것은 무엇이고, 어떻게 확인했나요? (예: 테스트 실행, 로그 대조)",
            "AI가 제안한 내용 중 채택하지 않은 것이 있다면 무엇이고, 이유는 무엇인가요?",
            "동료가 같은 결과를 얻으려면 AI에게 어떤 지시를 어떤 순서로 주면 되나요?");

    /** 공용 후속 질문 중 앞의 2개. 3번째는 과제마다 AI 가 만든 질문(Draft 의 taskFollowUp)을 쓴다 */
    private static final int COMMON_FOLLOW_UPS = 2;

    /** 검수자가 볼 수 있게 HIDDEN 으로 저장하는 그럴듯한 오답의 폴더 */
    public static final String SOLO_DIR = "_gen/solo/";
    public static final String WRONG_FIX_DIR = "_gen/wrong-fixes/";
    /** 정답과 구조가 다른 올바른 풀이(altSolutions). 검수자가 숨은 테스트가 공정한지 볼 수 있게 HIDDEN 으로 저장 */
    public static final String ALT_SOLUTION_DIR = "_gen/alt-solutions/";

    /** 60분 기준 d4 시점(분). 시간이 짧으면 비례해서 줄인다 */
    private static final List<Integer> D4_BASE = List.of(20, 30, 40, 50);

    public static List<Integer> d4Minutes(int minutes) {
        return D4_BASE.stream().map(m -> m * minutes / 60).toList();
    }

    private final TaskRepository records;
    private final TaskFileRepository files;
    private final TaskQuestionRepository questions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final PdfService pdf;
    private final TaskFileStorage storage;
    private final QuestionChoiceRepository choices;
    private final ShortAnswerKeyRepository answerKeys;
    private final QuestionSourceRepository sources;
    private final DocumentPageRepository pages;
    private final TransactionTemplate tx;

    public GeneratedTaskRepository(TaskRepository records, TaskFileRepository files,
            TaskQuestionRepository questions, JdbcTemplate jdbc, ObjectMapper json, Clock clock, PdfService pdf,
            TaskFileStorage storage, QuestionChoiceRepository choices, ShortAnswerKeyRepository answerKeys,
            QuestionSourceRepository sources, DocumentPageRepository pages, PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.pdf = pdf;
        this.storage = storage;
        this.choices = choices;
        this.answerKeys = answerKeys;
        this.sources = sources;
        this.pages = pages;
        this.records = records;
        this.files = files;
        this.questions = questions;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** 저장하고 과제 code 를 돌려준다. code 가 겹치면 -2, -3 … 을 붙인다 */
    public String saveDev(TaskGenRequest form, DevDraftDto d, TaskGeneratorService.GenReport report, long adminId) {
        return saveDev(form, d, report, adminId, Map.of());
    }

    /** soloFiles: "AI 혼자 바로 풀림" 검사에서 AI 가 낸 풀이. 키는 "1/src/x/Y.java"(시도 번호/경로) */
    public String saveDev(TaskGenRequest form, DevDraftDto d, TaskGeneratorService.GenReport report, long adminId, Map<String, String> soloFiles) {
        return withCodeRetry(attempt -> tx.execute(status -> saveDevOnce(form, d, report, adminId, soloFiles, attempt)));
    }

    /** 한 번의 시도(= 한 트랜잭션). attempt 가 클수록 code 번호를 건너뛴다 */
    private String saveDevOnce(TaskGenRequest form, DevDraftDto d, TaskGeneratorService.GenReport report, long adminId,
            Map<String, String> soloFiles, int attempt) {
        String code = uniqueCode(d.code(), d.title(), "dev-task", attempt);
        Instant now = clock.instant();
        long id = nextTaskId();

        Task t = new Task(id, code, cut(d.title(), 200), "DEV", form.minutes(), form.usageValue(),
                Task.Status.HIDDEN, null, adminId, now);
        t.setCard(cut(orElse(d.summary(), d.title()), 200), cut(orElse(d.category(), "기능 구현"), 30), TaskGenRequest.difficultyOf(form.minutes()));
        t.setContent(String.join("\n", d.description()), String.join("\n", d.requirements()), settings(d, form));
        records.save(t);

        List<TaskFile> rows = new ArrayList<>();
        for (var f : d.files()) rows.add(file(id, f.path(), f.content(), TaskFile.VISIBLE, false));
        for (var f : d.hiddenTests()) rows.add(file(id, f.path().substring("test/".length()), f.content(), TaskFile.HIDDEN, true));
        rows.add(file(id, "answer.md", d.answer(), TaskFile.HIDDEN, true));
        for (var f : d.solution()) rows.add(file(id, SOLUTION_DIR + f.path() + ".txt", f.content(), TaskFile.HIDDEN, true));
        rows.add(file(id, REPORT_PATH, toJson(report), TaskFile.HIDDEN, true));
        // 정답 코드처럼 .java 로 컴파일되지 않게 끝에 .txt 를 붙인다
        soloFiles.forEach((path, content) -> rows.add(file(id, SOLO_DIR + path + ".txt", content, TaskFile.HIDDEN, true)));
        if (d.wrongFixes() != null) {
            int n = 0;
            for (var w : d.wrongFixes()) {
                n++;
                String dir = WRONG_FIX_DIR + n + "/";
                rows.add(file(id, dir + "reason.md", orElse(w.reason(), "(이유 없음)"), TaskFile.HIDDEN, true));
                // 정답 코드처럼 .java 로 컴파일되지 않게 끝에 .txt 를 붙인다
                if (w.files() != null) for (var f : w.files()) rows.add(file(id, dir + f.path() + ".txt", f.content(), TaskFile.HIDDEN, true));
            }
        }
        if (d.altSolutions() != null) {
            int n = 0;
            for (var a : d.altSolutions()) {
                n++;
                String dir = ALT_SOLUTION_DIR + n + "/";
                rows.add(file(id, dir + "note.md", orElse(a.note(), "(설명 없음)"), TaskFile.HIDDEN, true));
                if (a.files() != null) for (var f : a.files()) rows.add(file(id, dir + f.path() + ".txt", f.content(), TaskFile.HIDDEN, true));
            }
        }
        files.saveAll(rows);

        // 앞의 공용 질문 2개 + 이 과제의 함정에 맞춘 질문. 맞춤 질문이 비었으면(검사 실패) 공용 3번째 문구를 쓴다
        List<String> followUps = new ArrayList<>(DEV_FOLLOW_UPS.subList(0, COMMON_FOLLOW_UPS));
        followUps.add(d.taskFollowUp() == null || d.taskFollowUp().isBlank() ? DEV_FOLLOW_UPS.get(COMMON_FOLLOW_UPS) : d.taskFollowUp().strip());
        for (int i = 0; i < followUps.size(); i++) {
            questions.save(new TaskQuestion(id, TaskQuestion.FOLLOWUP, "ESSAY", i + 1, followUps.get(i)));
        }
        return code;
    }

    /** 비개발 배점: 객관식 5점, 단답형 3점 */
    public static final int POINTS_SINGLE = 5;
    public static final int POINTS_SHORT = 3;
    public static final List<String> BIZ_HEADINGS = List.of("## 결론", "## 근거", "## 리스크");

    /**
     * 비개발 과제를 저장한다 (한 트랜잭션). 파일 경로 규칙은 pricing-plan 시드(V6)와 같다.
     * 자료 CSV 는 그대로 VISIBLE, 기획안.md 는 제목만 있는 틀 VISIBLE, checklist.md 와 _gen/report.json 은 HIDDEN.
     * 문서는 PDF 로 만들어 저장소에 올리고(storage_key, content 비움) 쪽 글은 document_pages 에 넣는다.
     * DB 저장이 실패하면(롤백) 이미 올린 PDF 도 지운다.
     */
    public String saveBiz(TaskGenRequest form, BizDraftDto d, TaskGeneratorService.GenReport report, long adminId) {
        return withCodeRetry(attempt -> tx.execute(status -> saveBizOnce(form, d, report, adminId, attempt)));
    }

    private String saveBizOnce(TaskGenRequest form, BizDraftDto d, TaskGeneratorService.GenReport report, long adminId, int attempt) {
        List<String> uploaded = new ArrayList<>();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) uploaded.forEach(storage::delete);
                }
            });
        }
        String code = uniqueCode(d.code(), d.title(), "biz-task", attempt);
        Instant now = clock.instant();
        long id = nextTaskId();

        Task t = new Task(id, code, cut(d.title(), 200), "BIZ", form.minutes(), form.usageValue(),
                Task.Status.HIDDEN, null, adminId, now);
        t.setCard(cut(orElse(d.summary(), d.title()), 200), cut(orElse(d.category(), "기획안 작성"), 30), TaskGenRequest.difficultyOf(form.minutes()));
        t.setContent(String.join("\n", d.description()), String.join("\n", d.requirements()), bizSettings(form));
        records.save(t);

        Map<String, Long> fileIds = new LinkedHashMap<>();
        for (var f : d.csvFiles()) {
            TaskFile r = files.save(new TaskFile(id, f.path(), f.content(), "text/csv",
                    f.content().getBytes(StandardCharsets.UTF_8).length, TaskFile.VISIBLE, false));
            fileIds.put(f.path(), r.getId());
        }
        int n = 0;
        for (var doc : d.documents()) {
            PdfService.Result made = pdf.make(BizCheckService.titleOf(doc.path()), doc.pages());
            String key = TaskFileStorage.keyOf(id, ++n);
            uploaded.add(key);
            storage.put(key, made.bytes());
            TaskFile r = new TaskFile(id, doc.path(), null, "application/pdf", made.bytes().length,
                    TaskFile.VISIBLE, true);
            r.setStorageKey(key);
            r = files.save(r);
            fileIds.put(doc.path(), r.getId());
            // 생성한 글을 그대로 쪽별로 넣는다 (형식 검사가 PDF 쪽 수 = 글 쪽 수를 확인했다)
            for (int i = 0; i < doc.pages().size(); i++) pages.save(new DocumentPage(r.getId(), i + 1, doc.pages().get(i)));
        }
        files.save(file(id, "기획안.md", "# " + d.title() + "\n\n" + String.join("\n\n", BIZ_HEADINGS) + "\n", TaskFile.VISIBLE, false));
        files.save(file(id, "checklist.md", d.checklist(), TaskFile.HIDDEN, true));
        files.save(file(id, REPORT_PATH, toJson(report), TaskFile.HIDDEN, true));

        int seq = 1;
        for (var q : d.questions()) {
            TaskQuestion qr = new TaskQuestion(id, "TASK", q.type().name(), seq++, q.content());
            qr.setPoints(q.type() == BizDraftDto.Type.SINGLE ? POINTS_SINGLE : POINTS_SHORT);
            qr = questions.save(qr);
            Long qid = qr.getId();
            if (q.type() == BizDraftDto.Type.SINGLE) {
                for (int i = 0; i < q.choices().size(); i++) {
                    choices.save(new QuestionChoice(qid, i + 1, q.choices().get(i).content(), q.choices().get(i).correct()));
                }
            } else {
                for (String a : new LinkedHashSet<>(q.accepted().stream().map(String::strip).toList())) {
                    answerKeys.save(new ShortAnswerKey(qid, a));
                }
            }
            Map<Long, String> pagesByFile = new LinkedHashMap<>();
            for (var s : q.sources()) {
                pagesByFile.merge(fileIds.get(s.path()), s.pages() == null ? "" : s.pages().strip(), (a, b) -> a.isEmpty() ? b : a + "," + b);
            }
            pagesByFile.forEach((fileId, pg) -> sources.save(new QuestionSource(qid, fileId, pg.isEmpty() ? null : cut(pg, 50))));
        }
        for (int i = 0; i < d.followUps().size(); i++) {
            questions.save(new TaskQuestion(id, TaskQuestion.FOLLOWUP, "ESSAY", i + 1, d.followUps().get(i)));
        }
        return code;
    }

    private String bizSettings(TaskGenRequest form) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("openFile", "기획안.md");
        s.put("editableFile", "기획안.md");
        s.put("requiredHeadings", BIZ_HEADINGS);
        s.put("d4Minutes", d4Minutes(form.minutes()));
        putAiLimits(s, form.minutes());
        s.put("competencies", form.competencies() == null ? List.of() : form.competencies());
        return toJson(s);
    }

    /** 과제 콘솔이 보여 줄 확인 결과(report.json 내용). 없으면 NoSuchElementException(404) */
    @Transactional(readOnly = true)
    public String report(String code) {
        Task t = records.findByCode(code).orElseThrow(() -> new NoSuchElementException("없는 과제입니다: " + code));
        Optional<TaskFile> f = files.findByTaskIdAndPath(t.getId(), REPORT_PATH);
        return f.map(TaskFile::getContent).orElseThrow(() -> new NoSuchElementException("AI 생성 확인 결과가 없는 과제입니다: " + code));
    }

    /** 저장 시도 최대 횟수. 동시에 같은 code 로 저장하면 유니크 제약이 걸려 다음 번호로 다시 시도한다 */
    static final int MAX_CODE_ATTEMPTS = 5;

    /**
     * code 중복(tasks.code 유니크 위반)으로 실패하면 다음 번호로 처음부터 다시 저장한다.
     * 시도마다 새 트랜잭션이라, 실패한 시도의 tasks/task_files 는 롤백으로 남지 않고 과제 id 도 다시 받는다.
     * 이 메서드는 트랜잭션 밖에서 불려야 한다(바깥 트랜잭션이 있으면 롤백 뒤 이어 쓸 수 없다).
     */
    private String withCodeRetry(IntFunction<String> attemptSave) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return attemptSave.apply(attempt);
            } catch (DataIntegrityViolationException e) {
                if (!isCodeConflict(e)) throw e;
                last = e;
            }
        }
        throw last;
    }

    /** 유니크 제약 위반인지(SQLState 23505, H2 도 같음). 다른 무결성 오류(길이 초과 등)는 재시도해도 소용없다 */
    private static boolean isCodeConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
            if (t.getClass().getSimpleName().contains("ConstraintViolation") && String.valueOf(t.getMessage()).toLowerCase().contains("code")) return true;
        }
        return false;
    }

    private String uniqueCode(String wanted, String title, String fallback, int attempt) {
        String base = slug(wanted);
        if (base.isEmpty()) base = slug(title);
        if (base.isEmpty()) base = fallback;
        // 첫 시도는 base, 다시 시도할 때는 이미 충돌한 번호를 건너뛴다 (조회에 안 보여도 -2, -3 … 로 올라간다)
        int n = attempt == 0 ? 1 : attempt + 1;
        String code = n == 1 ? base : base + "-" + n;
        while (records.findByCode(code).isPresent()) code = base + "-" + (++n);
        return code;
    }

    /** 영문 소문자·숫자·하이픈만 남긴다 */
    public static String slug(String s) {
        if (s == null) return "";
        String v = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return v.length() > 40 ? v.substring(0, 40).replaceAll("-+$", "") : v;
    }

    private String settings(DevDraftDto d, TaskGenRequest form) {
        String open = d.files().stream().map(DevDraftDto.FileDraft::path).filter(p -> p.startsWith("src/")).findFirst().orElse(null);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("openFile", open);
        s.put("editableFile", null);
        s.put("requiredHeadings", List.of());
        s.put("d4Minutes", d4Minutes(form.minutes()));
        putAiLimits(s, form.minutes());
        s.put("competencies", form.competencies() == null ? List.of() : form.competencies());
        return toJson(s);
    }

    /** E6: 과제별 AI 제한(턴·한 번에 첨부·첨부 맥락)을 제한시간 기본값으로 settings 에 함께 저장한다 */
    private static void putAiLimits(Map<String, Object> s, int minutes) {
        AiLimitsDto l = AiLimitsDto.forMinutes(minutes);
        s.put("aiTurns", l.turns());
        s.put("aiMaxAttach", l.maxAttach());
        s.put("aiContextChars", l.contextChars());
    }

    /** tasks.task_id 는 PostgreSQL 에서는 시퀀스, 테스트 DB(H2)에서는 최댓값+1 로 정한다 */
    private long nextTaskId() {
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        Long id = "PostgreSQL".equals(product)
                ? jdbc.queryForObject("select nextval(pg_get_serial_sequence('tasks', 'task_id'))", Long.class)
                : jdbc.queryForObject("select coalesce(max(task_id), 0) + 1 from tasks", Long.class);
        return id == null ? 1 : id;
    }

    private static TaskFile file(long taskId, String path, String content, String visibility, boolean readOnly) {
        return new TaskFile(taskId, path, content, mime(path), content.getBytes(StandardCharsets.UTF_8).length, visibility, readOnly);
    }

    private static String mime(String path) {
        if (path.endsWith(".java")) return "text/x-java-source";
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String orElse(String s, String other) {
        return s == null || s.isBlank() ? other : s.strip();
    }
}
