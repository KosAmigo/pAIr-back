package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloBizAnswerDto;
import com.pair.domain.taskgen.dto.SoloBizItemDto;
import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 참고 지표(BIZ) "AI 단독 풀이" (기준 문서 pAIr-docs 설계/좋은_과제_기준.md 3장 참고 지표). 합격·불합격이 아니라 기록이라 절대 fail 이 아니다.
 * 응시자가 하듯 과제 설명·요구사항·자료(PDF 글, CSV)·문항을 응시 화면과 같은 AI 모델에 그대로 붙여 넣어 한 번에 답하게 하고,
 * 그 답을 손대지 않고 채점해 함정 문항을 모두 맞히면 "숨은 결함을 잡음", 하나라도 틀리면 "지나침"으로 기록한다.
 * AI 호출이 실패해도 warn(생성을 막지 않는다). 다른 fail 검사가 모두 통과한 뒤에만 1회 돈다(BizTaskGeneratorService.afterChecks).
 * <p>
 * 함정 문항: 모든 단답형(문항마다 흔한 오답=함정에 빠진 값이 있다) + 보기가 trap.correctConclusion·wrongConclusion 과 같은 결론 객관식(M3).
 * 기존 "다른 회사 모델이 풀기"(BizCheckService.SOLVE)는 M1(풀 수 있는지)이고 이 기록과 별개다.
 */
@Component
public class BizSoloSolveService {

    /** 옛 이름은 "AI 답을 그대로 내면 함정에 걸림"(M5 필수 조건이던 때). 프런트는 이 이름을 따로 쓰지 않는다 */
    static final String NAME = "참고: AI 단독 풀이";
    static final String REQUEST = "위 문항에 답해 주세요. 기획안이나 보고서 같은 글은 쓰지 말고 문항에만 답하세요. "
            + "answers 에 문항마다 {\"no\": 문항 번호, \"answer\": 답} 한 개씩 담아 주세요. 객관식 answer 는 보기 번호, 단답형 answer 는 문항이 정한 단위의 숫자만 쓰세요.";
    static final String UNMATCHED = "AI 의 답을 문항에 맞출 수 없어 확인하지 못했어요(문항 번호가 맞지 않거나 빠짐)";

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

    /** 응시자가 AI 에 붙여 넣을 만한 글(제한 없이 자료 전부). 정답, 계산식, 체크리스트, 함정 정보는 넣지 않는다 */
    static String prompt(BizDraftDto d) {
        return prompt(d, null);
    }

    /** limits 가 있으면 자료를 앞에서부터 붙이되 첨부 개수·맥락 글자 한도까지만 붙인다(E6, 고르지 않는 풀이) */
    static String prompt(BizDraftDto d, AiLimitsDto limits) {
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(String.join("\n", d.description())).append("\n\n[요구사항]\n");
        for (String r : d.requirements()) sb.append("- ").append(r).append('\n');
        for (var m : SoloLimitService.attach(materials(d), limits).attached()) sb.append(m.text());
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

    /** 붙일 자료(문서 → CSV 순서, 파일 하나가 첨부 하나). 문서는 쪽마다 머리글을 붙인다 */
    private static List<SoloLimitService.Material> materials(BizDraftDto d) {
        List<SoloLimitService.Material> out = new ArrayList<>();
        for (var doc : d.documents()) {
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < doc.pages().size(); i++) {
                t.append("\n[자료 ").append(doc.path()).append(' ').append(i + 1).append("쪽]\n").append(doc.pages().get(i)).append('\n');
            }
            out.add(new SoloLimitService.Material(doc.path(), t.toString()));
        }
        for (var f : d.csvFiles()) out.add(new SoloLimitService.Material(f.path(), "\n[자료 " + f.path() + "]\n" + f.content() + "\n"));
        return out;
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

    /** 함정 문항을 모두 맞히면 "잡음", 하나라도 틀리면 "지나침"을 기록한다(항상 ok, warn 등급) */
    public Result run(BizDraftDto d) {
        return run(d, null);
    }

    /** limits: 과제의 AI 제한(E6). null 이면 자료를 제한 없이 모두 붙인다 */
    public Result run(BizDraftDto d, AiLimitsDto limits) {
        String model = props.soloModel() == null || props.soloModel().isBlank() ? pair.assistant().model() : props.soloModel();
        String system = pair.assistant().systemPrompt() == null ? "" : pair.assistant().systemPrompt();
        TaskGenAiService.Output<SoloBizAnswerDto> o;
        try {
            o = ai.generate(model, system, prompt(d, limits), SoloBizAnswerDto.class, (int) pair.assistant().maxOutputTokens(), pair.assistant().reasoningEffort());
        } catch (RuntimeException e) {
            return new Result(unchecked("모델 호출 실패: " + e.getMessage()), 0, 0, List.of());
        }
        List<SoloBizItemDto> items = o.value() == null || o.value().answers() == null ? List.of() : o.value().answers();
        if (items.isEmpty()) return new Result(unchecked("풀이 모델이 답을 주지 않음"), o.inputTokens(), o.outputTokens(), List.of());
        List<String> answers = matchByNumber(items, d.questions().size());
        if (answers == null) {
            // 번호가 문항과 맞지 않거나 빠졌다: 틀림으로 세지 않고 확인하지 못한 것으로 둔다. 기록에는 받은 그대로 남긴다
            List<String> raw = items.stream().map(it -> it == null ? null : it.no() + ":" + it.answer()).toList();
            return new Result(new GenCheckResultDto(NAME, true, UNMATCHED, GenCheckResultDto.WARN), o.inputTokens(), o.outputTokens(), raw);
        }

        List<Integer> traps = trapQuestions(d);
        List<String> missed = new ArrayList<>(), passed = new ArrayList<>();
        for (int i : traps) {
            var q = d.questions().get(i);
            String a = answers.get(i);
            String line = "Q" + (i + 1) + "(AI 답 " + a + ", 정답 " + correctText(q) + ")";
            (isRight(q, a) ? passed : missed).add(line);
        }
        GenCheckResultDto check;
        if (!missed.isEmpty()) {
            check = new GenCheckResultDto(NAME, true, "참고: AI 단독 풀이가 숨은 결함을 지나쳤어요. " + limitNote(d, limits) + "자료를 그대로 받고 낸 답이 함정 문항에서 틀렸어요: " + String.join(", ", missed), GenCheckResultDto.WARN);
        } else {
            String evidence = d.trap() == null || d.trap().evidence() == null ? "" : d.trap().evidence().strip();
            String detail = "참고: AI 단독 풀이(" + model + ")가 숨은 결함을 잡았어요. " + limitNote(d, limits) + "(함정 문항 " + passed.size() + "개를 모두 맞힘): "
                    + String.join(", ", passed) + ". 난이도가 낮거나 단서가 자료에 많이 드러났는지 검수하세요."
                    + (evidence.isEmpty() ? "" : " AI 가 단서로 썼을 만한 자료 문장: " + (evidence.length() > 150 ? evidence.substring(0, 150) + "…" : evidence));
            check = new GenCheckResultDto(NAME, true, detail, GenCheckResultDto.WARN);
        }
        return new Result(check, o.inputTokens(), o.outputTokens(), answers);
    }

    /**
     * 번호(1부터)로 문항 순서의 답 목록을 만든다. 번호가 없거나 문항 범위 밖인 항목(기획안 같은 덧붙인 글)은 무시한다.
     * 같은 문항 번호가 겹치거나 문항이 하나라도 빠지면 맞출 수 없으니 null
     */
    static List<String> matchByNumber(List<SoloBizItemDto> items, int questionCount) {
        String[] byNo = new String[questionCount];
        for (var it : items) {
            if (it == null || it.no() == null || it.no() < 1 || it.no() > questionCount) continue;
            if (it.answer() == null || byNo[it.no() - 1] != null) return null;
            byNo[it.no() - 1] = it.answer();
        }
        for (String a : byNo) if (a == null) return null;
        return List.of(byNo);
    }

    /** 제한 안에서 무엇을 붙였는지(검수자가 난이도를 가늠하는 데 쓴다) */
    private static String limitNote(BizDraftDto d, AiLimitsDto limits) {
        return SoloLimitService.note(SoloLimitService.attach(materials(d), limits), limits);
    }

    private static GenCheckResultDto unchecked(String why) {
        return new GenCheckResultDto(NAME, true, "AI 단독 풀이를 확인하지 못했어요(" + why + ")", GenCheckResultDto.WARN);
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
