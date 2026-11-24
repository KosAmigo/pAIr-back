package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.LeakVerdictDto;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * M5 답 노출 AI 판정. 정규식이 못 잡는 다른 말투의 규칙·원인·계산 절차 문장을 싼 모델이 가려낸다(기준 문서 3장 '명세와 답 노출의 경계').
 * 위반이 있으면 fail(다시 생성 때 위반 문장을 프롬프트에 넣음), 판정 호출이 실패하면 warn(생성을 막지 않음).
 */
final class AnswerLeakJudgeService {

    static final String NAME = "답 노출 AI 판정"; // M5
    static final String NUMBERS = "자료 숫자가 서로 맞음"; // M1(같은 호출에서 함께 판정, 추가 호출 없음)

    // 기준 문서 3장 경계 문구를 줄인 판정 기준. '%' 를 쓰지 않는다
    private static final String SYSTEM = """
            당신은 과제 검수자입니다. 응시자에게 보이는 글이 숨은 결함의 답을 알려 주는지 판정합니다.
            위반: 왜 틀렸는지(원인), 어디를 고칠지(결함 위치), 어떻게 고칠지, 계산 순서·결합 방식(문항 문구가 계산법을 정하는 경우 포함), \
            "~해야 한다"·"~만으로 판단하지 않는다" 같은 지시, 결함을 피하는 기대 결과를 따로 떼어 강조한 규칙 문장(E3·M3: [요구사항]의 한 문장이 숨은 결함을 피하는 결과를 규칙으로 직접 적은 경우. 예: 결함이 "동점 이름 순서"일 때 "각 점수 구간의 이름은 알파벳 오름차순이다", 결함이 "건수 상한"일 때 "반환 건수는 n과 전체 건수 중 작은 값이다". 입력→기대 출력 예 속에 있는 것은 허용).
            허용: 무엇이 맞는 결과인지(입력과 기대 출력의 예, 항상 성립하는 조건, 현상·증상, 무엇을 세는지 집계 정의), \
            무엇을 만들지·어떤 순서로 할지 알려 주는 작업 지시, 선택지를 그대로 제시하는 판단 지점 문구(E2), 판단 지점의 근거 사실. 이것만으로는 위반이 아닙니다.
            위반은 숨은 결함의 원인·위치·고치는 법·계산 방법을 알려 줄 때만 성립합니다. 숨은 결함·판단 지점은 응시자에게 비공개 정보이며 판정 기준으로만 씁니다. 애매하면 위반이 아닙니다.
            위반마다 quote(보이는 글에서 그대로 복사한 문장), flaw(관련된 숨은 결함), why(한 줄 이유)를 leaks 에 담고, 없으면 빈 목록을 돌려줍니다.
            또 보이는 글 안의 숫자끼리 모순되는 곳을 contradictions 에 담습니다(M1): 부분이 전체보다 큼(부서 참여자가 재직 인원보다 많음), 합계가 안 맞음,             불가능한 날짜·개수. 근거가 모호하거나 설명이 붙은 차이는 모순이 아닙니다. quote 는 모순된 숫자가 든 문장이나 CSV 줄을 그대로 복사하고, 없으면 빈 목록.""";

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    AnswerLeakJudgeService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    /** M5 판정만 돌려준다(숫자 모순 결과는 judgeAll) */
    GenCheckResultDto judge(String visible, String flaws) {
        return judgeAll(visible, flaws).get(0);
    }

    /**
     * visible: 응시자에게 보이는 글, flaws: 생성기가 밝힌 숨은 결함·판단 지점. 던지지 않는다.
     * 한 번의 호출로 [M5 답 노출, M1 숫자 모순] 두 결과를 돌려준다. 모순이 있으면 fail, 호출 실패는 둘 다 warn
     */
    List<GenCheckResultDto> judgeAll(String visible, String flaws) {
        try {
            String user = "[응시자에게 보이는 글]\n" + visible + "\n\n[숨은 결함·판단 지점(비공개)]\n" + flaws;
            var out = ai.generate(props.leakModel(), SYSTEM, user, LeakVerdictDto.class, props.leakMaxTokens(), props.suggestEffort());
            String seen = GenTextService.squash(visible);
            var v = out.value();
            List<String> found = new ArrayList<>();
            // 보이는 글에 없는 문장(모델이 지어낸 인용)은 버린다
            for (var l : v == null || v.leaks() == null ? List.<LeakVerdictDto.Leak>of() : v.leaks()) {
                if (l == null || l.quote() == null || !seen.contains(GenTextService.squash(l.quote()))) continue;
                found.add("\"" + l.quote().strip() + "\" (결함: " + l.flaw() + " / " + l.why() + ")");
            }
            List<String> bad = new ArrayList<>();
            for (var c : v == null || v.contradictions() == null ? List.<LeakVerdictDto.Contradiction>of() : v.contradictions()) {
                if (c == null || c.quote() == null || !seen.contains(GenTextService.squash(c.quote()))) continue;
                bad.add("\"" + c.quote().strip() + "\" (" + c.why() + ")");
            }
            return List.of(
                    found.isEmpty() ? new GenCheckResultDto(NAME, true, "응시자에게 보이는 글이 결함의 원인·고치는 법·계산 절차를 알려 주지 않습니다.")
                            : new GenCheckResultDto(NAME, false, "다음 문장이 답을 알려 줍니다. 이 문장을 지우거나 현상·예시로만 바꾸세요: " + String.join(" / ", found)),
                    bad.isEmpty() ? new GenCheckResultDto(NUMBERS, true, "자료의 숫자끼리 모순되는 곳이 없습니다.")
                            : new GenCheckResultDto(NUMBERS, false, "자료의 숫자가 서로 모순됩니다. 숫자를 맞추고 그에 따른 계산·정답도 다시 맞추세요: " + String.join(" / ", bad)));
        } catch (RuntimeException e) {
            String why = "AI 판정을 하지 못했어요(" + e.getMessage() + ")";
            return List.of(new GenCheckResultDto(NAME, true, "답 노출 " + why, GenCheckResultDto.WARN),
                    new GenCheckResultDto(NUMBERS, true, "숫자 모순 " + why, GenCheckResultDto.WARN));
        }
    }

    static String visible(DevDraftDto d) {
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(join(d.description())).append("\n[요구사항]\n").append(join(d.requirements())).append('\n');
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f != null) sb.append("\n[파일 ").append(f.path()).append("]\n").append(f.content()).append('\n');
        }
        return sb.toString();
    }

    static String flaws(DevDraftDto d) {
        return flaws(d.hiddenFlaws(), d.judgmentPoints(), "");
    }

    static String visible(BizDraftDto d) {
        StringBuilder sb = new StringBuilder();
        sb.append("[설명]\n").append(join(d.description())).append("\n[요구사항]\n").append(join(d.requirements())).append('\n');
        for (var doc : d.documents() == null ? List.<BizDraftDto.DocDraft>of() : d.documents()) {
            if (doc != null) sb.append("\n[문서 ").append(doc.path()).append("]\n").append(join(doc.pages())).append('\n');
        }
        for (var f : d.csvFiles() == null ? List.<DevDraftDto.FileDraft>of() : d.csvFiles()) {
            if (f != null) sb.append("\n[CSV ").append(f.path()).append("]\n").append(f.content()).append('\n');
        }
        int n = 0;
        for (var q : d.questions() == null ? List.<BizDraftDto.QuestionDraft>of() : d.questions()) {
            if (q == null) continue;
            sb.append("\n[문항 ").append(++n).append("] ").append(q.content());
            if (q.choices() != null) q.choices().forEach(c -> sb.append("\n- ").append(c.content()));
            sb.append('\n');
        }
        return sb.append("\n[후속 질문]\n").append(join(d.followUps())).toString();
    }

    static String flaws(BizDraftDto d) {
        return flaws(d.hiddenFlaws(), d.judgmentPoints(), d.trap() == null ? "" : "함정: " + d.trap().description() + "\n");
    }

    private static String flaws(List<DevDraftDto.HiddenFlaw> flaws, List<DevDraftDto.JudgmentPoint> points, String head) {
        StringBuilder sb = new StringBuilder(head);
        for (var f : flaws == null ? List.<DevDraftDto.HiddenFlaw>of() : flaws) if (f != null) sb.append("결함: ").append(f.description()).append('\n');
        for (var p : points == null ? List.<DevDraftDto.JudgmentPoint>of() : points) if (p != null) sb.append("판단 지점: ").append(p.question()).append('\n');
        return sb.toString();
    }

    private static String join(List<String> l) {
        return l == null ? "" : String.join("\n", l);
    }
}
