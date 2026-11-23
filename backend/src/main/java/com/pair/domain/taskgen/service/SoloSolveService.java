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
 * "AI 혼자 바로 풀리지 않음" 검사(warn). 응시자처럼 설명과 보이는 파일만 AI 에 붙여 넣고 고쳐 달라고 해서,
 * 그 답을 시작 코드에 덮어쓰고 공개+숨은 테스트를 돌린다. 숨은 테스트·정답·함정 정보는 AI 에 주지 않는다.
 * 2번(설정) 모두 통과하면 경고. 이 검사는 실패해도 과제를 다시 만들지 않고 생성도 멈추지 않는다.
 * 실행은 각 시도마다 CodeRunner 한 번이며, 시간 제한은 러너의 제한을 그대로 쓴다
 * (기존 "각 실행 30초 안" 검사에는 넣지 않고, 30초를 넘긴 시도는 통과로 치지 않는다).
 */
@Component
public class SoloSolveService {

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

    /** 형식이 맞는 Draft 로만 부른다 */
    public Result run(DevDraftDto d) {
        String model = props.soloModel() == null || props.soloModel().isBlank() ? pair.assistant().model() : props.soloModel();
        String system = pair.assistant().systemPrompt() == null ? "" : pair.assistant().systemPrompt();
        String user = prompt(d);
        int attempts = props.soloAttempts();
        Map<String, String> files = new LinkedHashMap<>();
        long in = 0, out = 0;
        int solved = 0, failedHidden = 0, callFailed = 0;

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
            if (!answer.isEmpty() && r.allPassed() && r.millis() < DevCheckService.TIME_LIMIT_MS) solved++;
            else failedHidden++;
        }

        GenCheckResultDto check;
        if (failedHidden > 0) {
            check = new GenCheckResultDto(NAME, true, attempts + "번 중 " + failedHidden + "번 숨은 테스트에서 실패(검증이 필요한 과제)", GenCheckResultDto.WARN);
        } else if (callFailed > 0) {
            check = new GenCheckResultDto(NAME, true, "AI 단독 풀이를 확인하지 못했어요(호출 실패)", GenCheckResultDto.WARN);
        } else {
            check = new GenCheckResultDto(NAME, false, "응시자 AI(" + model + ")가 설명만 보고 " + attempts
                    + "번 모두 바로 풀었어요. 함정을 깊게 하거나 자가진단용으로 쓰세요", GenCheckResultDto.WARN);
        }
        return new Result(check, files, in, out);
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
