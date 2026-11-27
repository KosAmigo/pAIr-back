package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizBlueprintDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevBlueprintDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.FillerDto;
import com.pair.domain.taskgen.dto.TaskGenContentResponse;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Answer;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Blueprint;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Code;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Doc;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Example;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.FileInfo;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Filler;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Flaw;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Form;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.HiddenTest;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Judgment;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Question;
import com.pair.domain.taskgen.dto.TaskGenContentResponse.Writing;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만드는 중인 과제의 중간 결과를 읽어 요약한다. task_gen_calls 에서 작업의 호출을 한 번에 가져와 단계(stage)별로 가장 최근 DONE 결과를 고른다
 * (다시 만들기를 했으면 최신 시도가 보인다). label 이 있는 호출(관문 REVIEW 계열)은 제외한다. json 을 못 읽은 칸은 null.
 */
@Component
public class TaskGenContentService {

    static final int NOTES_MAX = 2000;
    private static final Pattern TEST_METHOD = Pattern.compile(
            "@(?:[\\w.]*\\.)?Test\\b(?:\\s*\\([^)]*\\))?(?:\\s+@\\w+(?:\\([^)]*\\))?)*\\s+(?:(?:public|protected|private|static|final)\\s+)*[\\w<>\\[\\],.?]+\\s+([\\p{L}\\p{N}_$]+)\\s*\\(");

    private final TaskGenJobRepository jobs;
    private final TaskGenCallRepository calls;
    private final ObjectMapper json;

    public TaskGenContentService(TaskGenJobRepository jobs, TaskGenCallRepository calls, ObjectMapper json) {
        this.jobs = jobs;
        this.calls = calls;
        this.json = json;
    }

    /** 없는 작업이면 NoSuchElementException(404). 다른 env 의 작업도 읽는다 */
    @Transactional(readOnly = true)
    public TaskGenContentResponse content(String jobId) {
        TaskGenJob job = jobs.findById(jobId).orElseThrow(() -> new NoSuchElementException("없는 작업입니다: " + jobId));
        return summarize(job, calls.findByJobId(jobId));
    }

    TaskGenContentResponse summarize(TaskGenJob job, List<TaskGenCall> all) {
        TaskGenRequest f = read(job.getForm(), TaskGenRequest.class);
        boolean biz = f != null && "BIZ".equals(f.track());
        Form form = f == null ? null : new Form(f.track(), f.topic(), f.trap(), f.usage(), f.minutes(), job.getMode());
        // 생성 단계 호출만, 가장 최근 것이 앞에 오게
        Map<String, List<TaskGenCall>> byStage = new LinkedHashMap<>();
        all.stream().filter(c -> TaskGenCall.DONE.equals(c.getStatus()) && c.getResult() != null && c.getLabel() == null)
                .sorted(Comparator.comparing(TaskGenCall::getCreatedAt).reversed())
                .forEach(c -> byStage.computeIfAbsent(c.getStage(), k -> new ArrayList<>()).add(c));

        Instant updated = latest(job.getHeartbeatAt(), job.getFinishedAt(), job.getStartedAt(), job.getCreatedAt());
        for (TaskGenCall c : all) updated = latest(updated, c.getCreatedAt());

        return new TaskGenContentResponse(job.getId(), job.getStatus(), job.getStep(), updated, form,
                blueprint(byStage.get("BLUEPRINT"), biz),
                biz ? bizWriting(byStage.get("WRITING")) : devWriting(byStage.get("WRITING")),
                biz ? null : code(byStage.get("CODE")),
                biz ? bizAnswer(byStage.get("ANSWER")) : devAnswer(byStage.get("ANSWER")),
                biz ? questions(byStage.get("QUESTIONS")) : null,
                fillers(byStage.get("FILLER")));
    }

    // ── 단계별 요약 (calls 는 최신순) ──

    private Blueprint blueprint(List<TaskGenCall> list, boolean biz) {
        if (list == null) return null;
        for (TaskGenCall c : list) { // 가장 최근에 읽을 수 있는 것
            BlueprintDto b = biz ? convert(read(c.getResult(), BizBlueprintDto.class), BizBlueprintDto::toBlueprint)
                    : convert(read(c.getResult(), DevBlueprintDto.class), DevBlueprintDto::toBlueprint);
            if (b == null) continue;
            return new Blueprint(b.title(), b.summary(), b.scenario(), b.category(), nz(b.competencies()), nz(b.api()),
                    nz(b.examples()).stream().filter(Objects::nonNull).map(e -> new Example(e.input(), e.expected())).toList(),
                    nz(b.flaws()).stream().filter(Objects::nonNull).map(x -> new Flaw(x.what(), x.where())).toList(),
                    nz(b.judgments()).stream().filter(Objects::nonNull)
                            .map(j -> new Judgment(j.question(), nz(j.options()).stream().filter(Objects::nonNull).map(BlueprintDto.Option::text).toList())).toList(),
                    nz(b.neededFiles()),
                    nz(b.fillers()).stream().filter(Objects::nonNull).map(x -> new Filler(x.path(), x.summary(), x.confusingFlag())).toList(),
                    nz(b.docs()).stream().filter(Objects::nonNull).map(d -> new Doc(d.path(), d.role())).toList(),
                    nz(b.questionPlan()));
        }
        return null;
    }

    private Writing devWriting(List<TaskGenCall> list) {
        if (list == null) return null;
        for (TaskGenCall c : list) {
            DevWritingDto w = read(c.getResult(), DevWritingDto.class);
            if (w != null) return new Writing(nz(w.description()), nz(w.requirements()), cut(w.notes()), List.of());
        }
        return null;
    }

    /** BIZ 는 요청 글 호출(documents 없음)과 문서별 호출이 같은 단계에 섞여 있다: 설명·요구사항은 가장 최근 비어 있지 않은 것, 문서는 경로마다 가장 최근 것 */
    private Writing bizWriting(List<TaskGenCall> list) {
        if (list == null) return null;
        List<String> description = null;
        List<String> requirements = null;
        Map<String, Integer> docs = new LinkedHashMap<>();
        boolean any = false;
        for (TaskGenCall c : list) {
            BizWritingDto w = read(c.getResult(), BizWritingDto.class);
            if (w == null) continue;
            any = true;
            if (description == null && !nz(w.description()).isEmpty()) description = nz(w.description());
            if (requirements == null && !nz(w.requirements()).isEmpty()) requirements = nz(w.requirements());
            for (BizDraftDto.DocDraft d : nz(w.documents())) {
                if (d == null || d.path() == null) continue;
                docs.putIfAbsent(d.path(), nz(d.pages()).stream().filter(Objects::nonNull).mapToInt(String::length).sum());
            }
        }
        if (!any) return null;
        return new Writing(description == null ? List.of() : description, requirements == null ? List.of() : requirements, null,
                docs.entrySet().stream().map(e -> new FileInfo(e.getKey(), e.getValue())).toList());
    }

    private Code code(List<TaskGenCall> list) {
        if (list == null) return null;
        for (TaskGenCall c : list) {
            DevCodeDto d = read(c.getResult(), DevCodeDto.class);
            if (d != null) return new Code(files(d.files()));
        }
        return null;
    }

    private Answer devAnswer(List<TaskGenCall> list) {
        if (list == null) return null;
        for (TaskGenCall c : list) {
            DevAnswerDto a = read(c.getResult(), DevAnswerDto.class);
            if (a == null) continue;
            List<HiddenTest> tests = nz(a.hiddenTests()).stream().filter(Objects::nonNull).map(t -> new HiddenTest(t.path(), testMethods(t.content()))).toList();
            return new Answer(tests, nz(a.wrongFixes()).size(), nz(a.altSolutions()).size(), null);
        }
        return null;
    }

    private Answer bizAnswer(List<TaskGenCall> list) {
        if (list == null) return null;
        for (TaskGenCall c : list) {
            BizAnswerDto a = read(c.getResult(), BizAnswerDto.class);
            if (a == null) continue;
            return new Answer(List.of(), 0, 0, nz(a.keys()).stream().filter(Objects::nonNull).map(BizAnswerDto.Key::id).toList());
        }
        return null;
    }

    private List<Question> questions(List<TaskGenCall> list) {
        if (list == null) return null;
        for (TaskGenCall c : list) {
            BizQuestionsDto q = read(c.getResult(), BizQuestionsDto.class);
            if (q == null) continue;
            List<Question> out = new ArrayList<>();
            for (BizQuestionsDto.Question x : nz(q.questions())) {
                if (x == null) continue;
                out.add(new Question(out.size() + 1, nz(x.choices()).isEmpty() ? "단답형" : "객관식", x.content()));
            }
            return out;
        }
        return null;
    }

    /** 관련 적은 자료는 파일마다 따로 불러 저장되므로, 경로마다 가장 최근 것을 모은다 */
    private Code fillers(List<TaskGenCall> list) {
        if (list == null) return null;
        Map<String, FileInfo> byPath = new LinkedHashMap<>();
        for (TaskGenCall c : list) {
            FillerDto d = read(c.getResult(), FillerDto.class);
            if (d == null) continue;
            for (FileInfo fi : files(d.files())) byPath.putIfAbsent(fi.path(), fi);
        }
        return byPath.isEmpty() ? null : new Code(new ArrayList<>(byPath.values()));
    }

    // ── 도구 ──

    private static List<FileInfo> files(List<DevDraftDto.FileDraft> files) {
        return nz(files).stream().filter(Objects::nonNull).map(f -> new FileInfo(f.path(), f.content() == null ? 0 : f.content().length())).toList();
    }

    /** 테스트 파일에서 @Test 가 붙은 메서드 이름을 나온 순서대로 뽑는다 */
    static List<String> testMethods(String source) {
        List<String> out = new ArrayList<>();
        if (source == null) return out;
        Matcher m = TEST_METHOD.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String cut(String s) {
        return s == null || s.length() <= NOTES_MAX ? s : s.substring(0, NOTES_MAX) + "…";
    }

    private static Instant latest(Instant... ts) {
        Instant best = null;
        for (Instant t : ts) if (t != null && (best == null || t.isAfter(best))) best = t;
        return best;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static <A, B> B convert(A a, Function<A, B> f) {
        if (a == null) return null;
        try {
            return f.apply(a);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            return null;
        }
    }
}
