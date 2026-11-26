package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloAnswerDto;
import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.CodeRunner.RunResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 참고 지표(DEV) "AI 단독 풀이" (기준 문서 pAIr-docs 설계/좋은_과제_기준.md 3장 참고 지표). 합격·불합격이 아니라 기록이라 절대 fail 이 아니다.
 * 응시자처럼 설명과 보이는 파일만 AI 에 붙여 넣고 고쳐 달라고 해서, 그 답을 시작 코드에 덮어쓰고 공개+숨은 테스트를 돌린다.
 * 숨은 테스트까지 통과하면 "숨은 결함을 잡음", 실패하면 "숨은 결함을 지나침". 검수자가 난이도를 가늠하는 데 쓴다.
 * 숨은 테스트·정답·결함 정보는 AI 에 주지 않는다. 호출 실패도 warn(ok)이라 생성을 멈추지 않는다.
 * 비용을 아끼려고 다른 fail 검사가 모두 통과한 뒤에만 돌고(DevTaskGeneratorService.afterChecks), 기본 1회다.
 * 실행은 각 시도마다 CodeRunner 한 번이며, 시간 제한은 러너의 제한을 그대로 쓴다
 * (기존 "각 실행 30초 안" 검사에는 넣지 않고, 30초를 넘긴 시도는 통과로 치지 않는다).
 */
@Component
public class SoloSolveService {

    /** 참고 지표(DEV). 옛 이름은 "AI 혼자 바로 풀리지 않음"(M5 필수 조건이던 때). 프런트는 이 이름을 따로 쓰지 않는다 */
    static final String NAME = "참고: AI 단독 풀이";
    static final String REQUEST = "이 문제를 고쳐 주세요. 고친 src 파일의 전체 내용을 주세요. "
            + "기존 클래스·메서드의 이름, 매개변수, 반환형, 접근 범위(public·static·record 등)는 바꾸지 말고 그대로 두세요. 공개 테스트와 다른 파일이 고치지 않은 채로 컴파일되어야 합니다.";

    /** 검사 결과, 풀이 파일(경로는 "1/src/x/Y.java" 처럼 시도 번호가 앞에 붙음), 쓴 토큰 */
    public record Result(GenCheckResultDto check, Map<String, String> files, long inputTokens, long outputTokens) {}

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    private final PairProperties pair;
    private final CodeRunner runner;

    @org.springframework.beans.factory.annotation.Autowired
    public SoloSolveService(TaskGenAiService ai, TaskGenProperties props, PairProperties pair, TaskGenRunService run) {
        this(ai, props, pair, run.runner());
    }

    public SoloSolveService(TaskGenAiService ai, TaskGenProperties props, PairProperties pair, CodeRunner runner) {
        this.ai = ai;
        this.props = props;
        this.pair = pair;
        this.runner = runner;
    }

    public boolean enabled() {
        return props.soloEnabled();
    }

    /** 건너뛴 검사 한 줄 (통과도 실패도 아님) */
    public static GenCheckResultDto skipped(String why) {
        return new GenCheckResultDto(NAME, true, "건너뜀: " + why, GenCheckResultDto.WARN);
    }

    /** 응시자가 AI 에 붙여 넣을 만한 글. 숨은 테스트, 정답, 오답, 함정 핵심어는 넣지 않는다 */
    static String prompt(DevDraftDto d) {
        return prompt(d, (AiLimitsDto) null);
    }

    /** limits 가 있으면 파일을 앞에서부터 붙이되 첨부 개수·맥락 글자 한도까지만 붙인다(E6, 고르지 않는 풀이) */
    static String prompt(DevDraftDto d, AiLimitsDto limits) {
        return prompt(d, SoloLimitService.attach(materials(d), limits));
    }

    /** 과제 글(설명·요구사항)만. 고르기 질문과 풀이 질문이 같이 쓴다. 파일 내용(NOTES 포함)은 붙인 것만 따로 들어간다 */
    static String taskText(DevDraftDto d) {
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(String.join("\n", d.description())).append("\n\n");
        sb.append("[요구사항]\n");
        for (String r : d.requirements()) sb.append("- ").append(r).append('\n');
        return sb.toString();
    }

    /** 붙인 파일로 풀이 질문을 만든다 */
    static String prompt(DevDraftDto d, SoloLimitService.Attached attached) {
        StringBuilder sb = new StringBuilder(taskText(d));
        for (var m : attached.attached()) sb.append("\n[파일 ").append(m.path()).append("]\n").append(m.text()).append('\n');
        return sb.append('\n').append(REQUEST).toString();
    }

    private static List<SoloLimitService.Material> materials(DevDraftDto d) {
        List<SoloLimitService.Material> out = new ArrayList<>();
        for (var f : d.files()) out.add(new SoloLimitService.Material(f.path(), f.content()));
        return out;
    }

    /**
     * 풀이를 돌려 결과를 기록한다. 결과는 항상 ok(warn 등급)이고 다시 생성을 일으키지 않는다.
     * 숨은 테스트까지 모두 통과하면 "잡음", 한 번이라도 실패하면 "지나침"(풀린 경우엔 어디를 고쳐 풀렸는지 검수용으로 적는다)
     */
    public Result run(DevDraftDto d) {
        return run(d, null);
    }

    /** limits: 과제의 AI 제한(E6). null 이면 보이는 파일을 제한 없이 모두 붙인다 */
    public Result run(DevDraftDto d, AiLimitsDto limits) {
        String model = props.soloModel() == null || props.soloModel().isBlank() ? pair.assistant().model() : props.soloModel();
        String system = pair.assistant().systemPrompt() == null ? "" : pair.assistant().systemPrompt();
        // g8: AI 가 파일 목록을 보고 직접 골라 붙인다(SoloLimitService.pick). 제한이 없으면 모두 붙인다
        SoloLimitService.Picked picked = SoloLimitService.pick(ai, model, system, (int) pair.assistant().maxOutputTokens(), pair.assistant().reasoningEffort(), taskText(d), materials(d), limits);
        String user = prompt(d, picked.attached());
        String pickNote = SoloLimitService.note(picked, limits);
        int attempts = props.soloAttempts();
        Map<String, String> files = new LinkedHashMap<>();
        long in = picked.inputTokens(), out = picked.outputTokens();
        int solved = 0, failedHidden = 0, callFailed = 0, invalid = 0;
        String firstFix = null, failure = "", invalidWhy = "";

        for (int n = 1; n <= attempts; n++) {
            TaskGenAiService.Output<SoloAnswerDto> o;
            try {
                o = ai.generate(model, system, user, SoloAnswerDto.class, (int) pair.assistant().maxOutputTokens(), pair.assistant().reasoningEffort());
            } catch (RuntimeException e) {
                callFailed++;
                failure = "모델 호출 실패: " + e.getMessage();
                continue;
            }
            in += o.inputTokens();
            out += o.outputTokens();
            Map<String, String> answer = answerFiles(o.value());
            for (var e : answer.entrySet()) files.put(n + "/" + e.getKey(), e.getValue());
            Map<String, String> sources = DevRunService.toMap(d.files());
            sources.putAll(answer);
            sources.putAll(DevRunService.toMap(d.hiddenTests()));
            RunResult r;
            try {
                r = runner.run(sources);
            } catch (RuntimeException e) {
                callFailed++;
                failure = "풀이 실행 실패: " + e.getMessage();
                continue;
            }
            if (!answer.isEmpty() && r.allPassed() && r.millis() < DevCheckService.TIME_LIMIT_MS) {
                solved++;
                if (firstFix == null) firstFix = fixSummary(d, answer);
            } else {
                String why = invalidReason(d, answer, r);
                if (why == null) failedHidden++;
                else {
                    invalid++;
                    invalidWhy = why;
                }
            }
        }

        GenCheckResultDto check;
        String invalidNote = invalid > 0 ? " 무효 " + invalid + "번(" + invalidWhy + ")." : "";
        if (failedHidden == 0 && solved == 0 && invalid > 0) {
            // 풀이가 컴파일되지 않거나 결함과 상관없는 테스트에서 떨어짐: 난이도 신호가 아니다(g5 03: static 으로 바꿔 컴파일 실패). 놓침 비율 분모에서 뺀다
            check = new GenCheckResultDto(NAME, true, INVALID_PREFIX + invalidWhy + " (" + attempts + "번 중 " + invalid + "번). " + pickNote.strip(), GenCheckResultDto.WARN);
        } else if (failedHidden > 0) {
            check = new GenCheckResultDto(NAME, true, "참고: AI 단독 풀이가 숨은 결함을 지나쳤어요" + (limits == null ? "" : ". " + pickNote.strip() + " ") + "(" + attempts + "번 중 " + failedHidden
                    + "번 숨은 테스트에서 실패). 검증이 필요한 과제입니다." + invalidNote, GenCheckResultDto.WARN);
        } else if (callFailed > 0) {
            check = new GenCheckResultDto(NAME, true, "AI 단독 풀이를 확인하지 못했어요(" + failure + ")", GenCheckResultDto.WARN);
        } else {
            String detail = "참고: AI 단독 풀이(" + model + ")가 숨은 결함을 잡았어요(" + (solved == attempts ? attempts + "번 모두" : attempts + "번 중 " + solved + "번") + " 숨은 테스트 통과)." + invalidNote + " 난이도가 낮거나 단서가 자료에 많이 드러났는지 검수하세요. "
                    + pickNote + sourcesNote(d, picked.attached())
                    + (firstFix == null || firstFix.isBlank() ? "" : " AI 가 바로 고친 곳: " + firstFix + ".")
                    + clueNote(d, firstFix);
            check = new GenCheckResultDto(NAME, true, detail, GenCheckResultDto.WARN);
        }
        return new Result(check, files, in, out);
    }

    /** 무효 판정 상세의 앞말(TaskGeneratorService.soloOutcome 이 status invalid 로 읽는다) */
    static final String INVALID_PREFIX = "참고: AI 단독 풀이 무효: ";

    /**
     * 풀지 못한 시도가 놓침(missed)인지 무효인지. 놓침은 컴파일되고 공개 테스트를 모두 통과했는데 결함을 겨냥한 숨은 테스트(hiddenFlaws.caughtBy)가 실패한 경우뿐이다.
     * 무효면 그 이유, 놓침이면 null. 컴파일 실패, 시간 초과, 공개 테스트 실패, 결함 사례 밖 숨은 테스트만 실패한 경우가 무효다(고친 파일이 없으면 고칠 곳을 못 찾은 것이라 놓침)
     */
    static String invalidReason(DevDraftDto d, Map<String, String> answer, RunResult r) {
        if (answer.isEmpty()) return null; // 고칠 곳이 없다고 본 것: 결함을 놓친 것이다
        if (!r.compiled()) {
            String diag = r.diagnostics() == null || r.diagnostics().isEmpty() ? "" : ": " + r.diagnostics().get(0).file() + " " + r.diagnostics().get(0).message();
            return "풀이가 컴파일되지 않음" + (diag.length() > 160 ? diag.substring(0, 160) + "…" : diag);
        }
        if (r.millis() >= DevCheckService.TIME_LIMIT_MS) return "시간 초과";
        java.util.Set<String> publicClasses = new java.util.HashSet<>();
        for (var f : d.files()) if (f.path() != null && f.path().startsWith("test/") && f.path().endsWith(".java")) publicClasses.add(simpleClass(f.path()));
        java.util.Set<String> flawTests = new java.util.HashSet<>();
        for (var h : d.hiddenFlaws() == null ? List.<DevDraftDto.HiddenFlaw>of() : d.hiddenFlaws()) if (h.caughtBy() != null && !h.caughtBy().isBlank()) flawTests.add(h.caughtBy().strip());
        List<String> failed = r.tests().stream().filter(t -> !t.passed()).map(com.pair.infra.sandbox.CodeRunner.TestCase::name).toList();
        if (failed.isEmpty()) return null; // 테스트별 결과가 없으면 가를 수 없어 예전처럼 놓침으로 둔다
        List<String> publicFailed = failed.stream().filter(n -> publicClasses.contains(testClass(n))).toList();
        if (!publicFailed.isEmpty()) return "공개(기존) 테스트 실패 " + publicFailed;
        if (failed.stream().noneMatch(n -> flawTests.contains(testMethod(n)))) return "결함을 겨냥하지 않은 숨은 테스트만 실패 " + failed;
        return null;
    }

    private static String simpleClass(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        return base.endsWith(".java") ? base.substring(0, base.length() - 5) : base;
    }

    /** 테스트 이름 "패키지.클래스.메서드" 의 클래스 이름 */
    private static String testClass(String name) {
        String[] p = name.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : name;
    }

    private static String testMethod(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** AI 가 단서로 썼을 만한 요구사항·메모·summary 문장 목록 (없으면 직접 점검하라는 말) */
    static String clueNote(DevDraftDto d, String fixSummary) {
        List<String> clues = DevCheckService.clueSentences(d, fixSummary);
        if (clues.isEmpty()) return " AI 가 단서로 썼을 만한 문장을 자동으로 찾지 못했어요(설명·요구사항·NOTES 에서 원인 방향을 말하는 문장을 직접 점검).";
        return " AI 가 단서로 썼을 만한 문장: " + String.join(" / ", clues) + ".";
    }

    /** 풀이에 AI 가 본 자료. 요구사항·메모·로그가 정답을 알려 줬는지 검수자가 가늠하게 한다 */
    static String sourcesNote(DevDraftDto d) {
        return sourcesNote(d, SoloLimitService.attach(materials(d), null));
    }

    /** AI 가 실제로 본 자료: 설명·요구사항 + 붙인 파일만(g7 판정: 붙이지 않은 NOTES 까지 본 것처럼 적혀 헷갈렸음) */
    static String sourcesNote(DevDraftDto d, SoloLimitService.Attached attached) {
        return "AI 가 본 자료: 설명, 요구사항 " + (d.requirements() == null ? 0 : d.requirements().size()) + "줄, 붙인 파일 "
                + attached.attached().stream().map(SoloLimitService.Material::path).toList() + ".";
    }

    /** AI 풀이가 시작 코드에서 바꾼 줄(추가·삭제)을 짧게 줄인 글 */
    static String fixSummary(DevDraftDto d, Map<String, String> answer) {
        Map<String, String> start = DevRunService.toMap(d.files());
        StringBuilder sb = new StringBuilder();
        for (var e : answer.entrySet()) {
            String diff = DevCheckService.changeSummary(start.get(e.getKey()), e.getValue());
            if (diff.isBlank()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(e.getKey()).append(" ").append(diff);
        }
        return sb.length() > 500 ? sb.substring(0, 500) + "…" : sb.toString();
    }

    /** AI 답에서 src/ 아래 .java 경로만 받는다(테스트 파일을 덮어쓰거나 폴더 밖으로 나가는 경로는 버림) */
    private static Map<String, String> answerFiles(SoloAnswerDto a) {
        Map<String, String> m = new LinkedHashMap<>();
        List<DevDraftDto.FileDraft> list = a == null || a.files() == null ? new ArrayList<>() : a.files();
        for (var f : list) {
            if (f == null || f.path() == null || f.content() == null) continue;
            String p = f.path().strip();
            if (DevRunService.PATH.matcher(p).matches() && p.startsWith("src/") && !p.contains("//")) m.put(p, f.content());
        }
        return m;
    }
}
