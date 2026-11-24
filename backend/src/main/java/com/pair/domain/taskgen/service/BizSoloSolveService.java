package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloBizAnswerDto;
import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * M5(BIZ) "AI 답을 그대로 내면 함정에 걸림" 검사 (기준 문서 pAIr-docs 설계/좋은_과제_기준.md 2장 M5, 7장).
 * 응시자가 하듯 과제 설명·요구사항·자료(PDF 글, CSV)·문항을 응시 화면과 같은 AI 모델에 그대로 붙여 넣어 한 번에 답하게 하고,
 * 그 답을 손대지 않고 채점한다. 함정이 걸린 문항을 AI 가 모두 맞히면 "그대로 내도 통과"라서 용도(LIVE·SELF)와 상관없이 fail.
 * 함정 문항을 하나라도 틀리면 ok. AI 호출이 실패하면 warn(생성을 막지 않는다).
 * <p>
 * 함정 문항: 모든 단답형(문항마다 흔한 오답=함정에 빠진 값이 있다) + 보기가 trap.correctConclusion·wrongConclusion 과 같은 결론 객관식(M3).
 * 기존 "다른 회사 모델이 풀기"(BizCheckService.SOLVE)는 M1(풀 수 있는지)이고 이 검사와 별개다.
 */
@Component
public class BizSoloSolveService {

    /** 이름을 바꾸면 프런트 표시와 저장된 report.json 에 영향이 있다 */
    static final String NAME = "AI 답을 그대로 내면 함정에 걸림";
    static final String REQUEST = "위 문항에 답해 주세요. 객관식은 보기 번호, 단답형은 문항이 정한 단위의 숫자만 문항 순서대로 answers 에 담아 주세요.";

    /** 다시 생성할 때 AI 출제자에게 주는 지시 (검사 설명에 붙어 다음 시도 프롬프트로 간다) */
    static final String RETRY_INSTRUCTION = " [다시 설계 지시] 함정의 근거 문장은 자료에 사실로 그대로 두되, 한 번 읽으면 바로 반영되는 곳에 두지 말고 "
            + "서로 다른 문서(또는 CSV)의 숫자, 기간, 집계 범위를 대조해야 드러나게 다시 설계하세요. 정답 계산 절차나 해석 지시를 자료에 쓰면 안 되고, "
            + "자료를 일부러 꼬거나 자료에 없는 지식이 필요하게 만들면 안 됩니다.";

    /** answers 는 AI 가 낸 답(호출이 실패했으면 빈 목록) */
    public record Result(GenCheckResultDto check, long inputTokens, long outputTokens, List<String> answers) {}

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    private final PairProperties pair;

    public BizSoloSolveService(TaskGenAiService ai, TaskGenProperties props, PairProperties pair) {
        this.ai = ai;
        this.props = props;
        this.pair = pair;
    }

    public boolean enabled() {
        return props.soloEnabled();
    }

    /** 건너뛴 검사 한 줄 (통과도 실패도 아님) */
    public static GenCheckResultDto skipped(String why) {
        return new GenCheckResultDto(NAME, true, "건너뜀: " + why, GenCheckResultDto.WARN);
    }

    /** 응시자가 AI 에 붙여 넣을 만한 글. 정답, 계산식, 체크리스트, 함정 정보는 넣지 않는다 */
    static String prompt(BizDraftDto d) {
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(String.join("\n", d.description())).append("\n\n[요구사항]\n");
        for (String r : d.requirements()) sb.append("- ").append(r).append('\n');
        for (var doc : d.documents()) {
            for (int i = 0; i < doc.pages().size(); i++) {
                sb.append("\n[자료 ").append(doc.path()).append(' ').append(i + 1).append("쪽]\n").append(doc.pages().get(i)).append('\n');
            }
        }
        for (var f : d.csvFiles()) sb.append("\n[자료 ").append(f.path()).append("]\n").append(f.content()).append('\n');
        sb.append("\n[문항]\n");
        int n = 1;
        for (var q : d.questions()) {
            sb.append("Q").append(n++).append(". (").append(q.type() == BizDraftDto.Type.SINGLE ? "객관식" : "단답형").append(") ").append(q.content()).append('\n');
            if (q.type() == BizDraftDto.Type.SINGLE) {
                for (int j = 0; j < q.choices().size(); j++) sb.append("  ").append(j + 1).append(") ").append(q.choices().get(j).content()).append('\n');
            }
        }
        return sb.append('\n').append(REQUEST).toString();
    }

    /** 함정이 걸린 문항의 번호(0부터): 모든 단답형 + 결론 문구와 같은 보기를 가진 객관식 */
    static List<Integer> trapQuestions(BizDraftDto d) {
        String right = normalize(d.trap() == null ? null : d.trap().correctConclusion());
        String wrong = normalize(d.trap() == null ? null : d.trap().wrongConclusion());
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < d.questions().size(); i++) {
            var q = d.questions().get(i);
            if (q.type() == BizDraftDto.Type.SHORT) {
                out.add(i);
            } else if (q.choices().stream().anyMatch(c -> sameText(normalize(c.content()), right) || sameText(normalize(c.content()), wrong))) {
                out.add(i);
            }
        }
        return out;
    }

    private static boolean sameText(String choice, String conclusion) {
        if (choice.length() < 4 || conclusion.length() < 4) return false;
        return choice.contains(conclusion) || conclusion.contains(choice);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{Punct}。、·…]+", "").toLowerCase();
    }

    /** 함정 문항을 모두 맞히면 fail(다시 생성 대상). 용도(LIVE·SELF)와 상관없이 필수다(M5) */
    public Result run(BizDraftDto d) {
        String model = props.soloModel() == null || props.soloModel().isBlank() ? pair.assistant().model() : props.soloModel();
        String system = pair.assistant().systemPrompt() == null ? "" : pair.assistant().systemPrompt();
        TaskGenAiService.Output<SoloBizAnswerDto> o;
        try {
            o = ai.generate(model, system, prompt(d), SoloBizAnswerDto.class, (int) pair.assistant().maxOutputTokens(), pair.assistant().reasoningEffort());
        } catch (RuntimeException e) {
            return new Result(unchecked(), 0, 0, List.of());
        }
        List<String> answers = o.value() == null || o.value().answers() == null ? List.of() : o.value().answers();
        if (answers.isEmpty()) return new Result(unchecked(), o.inputTokens(), o.outputTokens(), answers);

        List<Integer> traps = trapQuestions(d);
        List<String> missed = new ArrayList<>(), passed = new ArrayList<>();
        for (int i : traps) {
            var q = d.questions().get(i);
            String a = i < answers.size() ? answers.get(i) : null;
            String line = "Q" + (i + 1) + "(AI 답 " + a + ", 정답 " + correctText(q) + ")";
            (isRight(q, a) ? passed : missed).add(line);
        }
        GenCheckResultDto check;
        if (!missed.isEmpty()) {
            check = new GenCheckResultDto(NAME, true, "AI 가 자료를 그대로 받고 낸 답이 함정 문항에서 틀렸어요: " + String.join(", ", missed), GenCheckResultDto.WARN);
        } else {
            String evidence = d.trap() == null || d.trap().evidence() == null ? "" : d.trap().evidence().strip();
            String detail = "응시자 AI(" + model + ")가 자료를 그대로 받고 한 번에 답했는데 함정 문항 " + passed.size() + "개를 모두 맞혔어요(그대로 내도 통과): "
                    + String.join(", ", passed) + "."
                    + (evidence.isEmpty() ? "" : " AI 가 단서로 썼을 만한 자료 문장: " + (evidence.length() > 150 ? evidence.substring(0, 150) + "…" : evidence))
                    + RETRY_INSTRUCTION;
            check = new GenCheckResultDto(NAME, false, detail, GenCheckResultDto.FAIL);
        }
        return new Result(check, o.inputTokens(), o.outputTokens(), answers);
    }

    private static GenCheckResultDto unchecked() {
        return new GenCheckResultDto(NAME, true, "AI 단독 풀이를 확인하지 못했어요(호출 실패)", GenCheckResultDto.WARN);
    }

    private static boolean isRight(BizDraftDto.QuestionDraft q, String answer) {
        if (answer == null) return false;
        if (q.type() == BizDraftDto.Type.SINGLE) {
            for (int j = 0; j < q.choices().size(); j++) if (q.choices().get(j).correct()) return answer.strip().equals(String.valueOf(j + 1));
            return false;
        }
        Double got = BizTableService.num(answer);
        return q.accepted().stream().anyMatch(x -> BizCheckService.close(got, BizTableService.num(x)));
    }

    private static String correctText(BizDraftDto.QuestionDraft q) {
        if (q.type() == BizDraftDto.Type.SHORT) return q.accepted().get(0);
        for (int j = 0; j < q.choices().size(); j++) if (q.choices().get(j).correct()) return String.valueOf(j + 1);
        return "?";
    }
}
