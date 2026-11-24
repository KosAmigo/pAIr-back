package com.pair.domain.taskgen.service;

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
 * M5(DEV) "AI 혼자 바로 풀리지 않음" 검사 (기준 문서 pAIr-docs 설계/좋은_과제_기준.md 2장 M5: AI 답을 그대로 내면 함정에 걸린다).
 * 풀리면 용도(LIVE·SELF)와 상관없이 fail. 응시자처럼 설명과 보이는 파일만 AI 에 붙여 넣고 고쳐 달라고 해서,
 * 그 답을 시작 코드에 덮어쓰고 공개+숨은 테스트를 돌린다. 숨은 테스트·정답·함정 정보는 AI 에 주지 않는다.
 * 2번(설정) 모두 통과하면 fail(다시 생성). 호출 실패와 일부 실패는 warn 이라 생성을 멈추지 않는다.
 * 실행은 각 시도마다 CodeRunner 한 번이며, 시간 제한은 러너의 제한을 그대로 쓴다
 * (기존 "각 실행 30초 안" 검사에는 넣지 않고, 30초를 넘긴 시도는 통과로 치지 않는다).
 */
@Component
public class SoloSolveService {

    /** M5(DEV). 이름을 바꾸면 프런트 표시와 저장된 report.json 에 영향이 있어 그대로 둔다 */
    static final String NAME = "AI 혼자 바로 풀리지 않음";
    static final String REQUEST = "이 문제를 고쳐 주세요. 고친 src 파일의 전체 내용을 주세요.";

    /** 검사 결과, 풀이 파일(경로는 "1/src/x/Y.java" 처럼 시도 번호가 앞에 붙음), 쓴 토큰 */
    public record Result(GenCheckResultDto check, Map<String, String> files, long inputTokens, long outputTokens) {}

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    private final PairProperties pair;
    private final CodeRunner runner;

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
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(String.join("\n", d.description())).append("\n\n");
        sb.append("[요구사항]\n");
        for (String r : d.requirements()) sb.append("- ").append(r).append('\n');
        for (var f : d.files()) sb.append("\n[파일 ").append(f.path()).append("]\n").append(f.content()).append('\n');
        return sb.append('\n').append(REQUEST).toString();
    }

    /**
     * 2번 모두 풀리면 fail(다시 생성 대상). 용도(LIVE·SELF)와 상관없이 필수다(M5).
     * 풀린 경우 설명에 "무슨 자료를 보고, 어디를 고쳐 풀렸는지"를 넣는다(추가 AI 호출 없이 이미 있는 답으로 만든다).
     */
    public Result run(DevDraftDto d) {
        String model = props.soloModel() == null || props.soloModel().isBlank() ? pair.assistant().model() : props.soloModel();
        String system = pair.assistant().systemPrompt() == null ? "" : pair.assistant().systemPrompt();
        String user = prompt(d);
        int attempts = props.soloAttempts();
        Map<String, String> files = new LinkedHashMap<>();
        long in = 0, out = 0;
        int solved = 0, failedHidden = 0, callFailed = 0;
        String firstFix = null;

        for (int n = 1; n <= attempts; n++) {
            TaskGenAiService.Output<SoloAnswerDto> o;
            try {
                o = ai.generate(model, system, user, SoloAnswerDto.class, (int) pair.assistant().maxOutputTokens(), pair.assistant().reasoningEffort());
            } catch (RuntimeException e) {
                callFailed++;
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
                continue;
            }
            if (!answer.isEmpty() && r.allPassed() && r.millis() < DevCheckService.TIME_LIMIT_MS) {
                solved++;
                if (firstFix == null) firstFix = fixSummary(d, answer);
            } else failedHidden++;
        }

        GenCheckResultDto check;
        if (failedHidden > 0) {
            check = new GenCheckResultDto(NAME, true, attempts + "번 중 " + failedHidden + "번 숨은 테스트에서 실패(검증이 필요한 과제)", GenCheckResultDto.WARN);
        } else if (callFailed > 0) {
            check = new GenCheckResultDto(NAME, true, "AI 단독 풀이를 확인하지 못했어요(호출 실패)", GenCheckResultDto.WARN);
        } else {
            String detail = "응시자 AI(" + model + ")가 설명만 보고 " + attempts + "번 모두 바로 풀었어요. " + sourcesNote(d)
                    + (firstFix == null || firstFix.isBlank() ? "" : " AI 가 바로 고친 곳: " + firstFix + ".")
                    + clueNote(d, firstFix)
                    + RETRY_INSTRUCTION;
            check = new GenCheckResultDto(NAME, false, detail, GenCheckResultDto.FAIL);
        }
        return new Result(check, files, in, out);
    }

    /** 다시 생성할 때 AI 출제자에게 주는 지시 (검사 설명에 붙어 다음 시도 프롬프트로 간다) */
    static final String RETRY_INSTRUCTION = " [다시 설계 지시] 위 단서 문장을 지우고(또는 현상만 남기고), 원인이 시작 코드의 한 줄에서 바로 보이지 않게 하세요"
            + "(예: return pages + 1; 처럼 눈에 띄는 한 줄 수정은 안 됩니다). 흔한 첫 수정은 공개 자료 사례는 통과하지만 숨은 테스트에서 걸리게 다시 설계하세요. "
            + "코드를 일부러 읽기 어렵게 꼬는 방식은 안 됩니다.";

    /** AI 가 단서로 썼을 만한 요구사항·메모·summary 문장 목록 (없으면 직접 점검하라는 말) */
    static String clueNote(DevDraftDto d, String fixSummary) {
        List<String> clues = DevCheckService.clueSentences(d, fixSummary);
        if (clues.isEmpty()) return " AI 가 단서로 썼을 만한 문장을 자동으로 찾지 못했어요(설명·요구사항·NOTES 에서 원인 방향을 말하는 문장을 직접 점검).";
        return " AI 가 단서로 썼을 만한 문장: " + String.join(" / ", clues) + ".";
    }

    /** 풀이에 AI 가 본 자료. 요구사항·메모·로그가 정답을 알려 줬는지 검수자가 가늠하게 한다 */
    static String sourcesNote(DevDraftDto d) {
        List<String> notes = new ArrayList<>();
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f != null && f.path() != null && !f.path().startsWith("src/") && !f.path().startsWith("test/")) notes.add(f.path());
        }
        return "AI 가 본 자료: 설명, 요구사항 " + (d.requirements() == null ? 0 : d.requirements().size()) + "줄, 시작 코드·공개 테스트"
                + (notes.isEmpty() ? "" : ", " + String.join(", ", notes)) + ".";
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
