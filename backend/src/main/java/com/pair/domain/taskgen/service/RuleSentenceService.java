package com.pair.domain.taskgen.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 규칙·정의 문장 알아보기. 설계도의 사실은 기록과 관측(무슨 일이 있었나, 표의 값, 견적·영수증·로그·메모가 말하는 것)뿐이고,
 * 작성 단계가 덧붙이는 글도 상황 설명뿐이어야 한다. 정의·공식·규칙 문장은 그 자체가 답이라(M5) 코드가 걸러 다시 쓰게 한다.
 * 기존 M5 정규식(BizCheckService)을 그대로 쓰고, 정의·결합·포함 꼴을 더한다. 오탐은 감수하고 다시 쓰게 한다(경고가 아니라 실패)
 */
final class RuleSentenceService {

    /** "~은/는 ~한 값/금액/지표다" 꼴 정의 */
    private static final Pattern DEFINITION = Pattern.compile("(은|는)[^.\\n]{0,80}(값|금액|지표|비율|비용|일평균|합계|수)(이다|입니다|다)\\s*[.。]?\\s*$");
    /** 값을 합치는 방법 */
    private static final Pattern COMBINE = Pattern.compile("(나눈|나누어|나눠|합한|합친|뺀|곱한|더한)");
    /** "~를 포함한다/포함된다" 규칙(영수증 항목 "부가세 포함 금액" 같은 명사 꼴은 아님) */
    private static final Pattern INCLUDE = Pattern.compile("포함(한다|합니다|된다|됩니다|하며|되며|되어\\s*있다|되어\\s*있습니다)");
    /** 작성 단계가 덧붙이기 쉬운 처리 규칙·용도 문장(판단 지점을 닫는 말 포함) */
    private static final Pattern WRITER_RULE = Pattern.compile("(취급|적용|계산)(한다|합니다|하며|됩니다|된다)|문자\\s*그대로|(방식|용도)(이다|입니다)|사용(된다|됩니다)");

    private RuleSentenceService() {}

    /** 설계도 사실로 쓸 수 없는 규칙·정의 문장인가 */
    static boolean ruleLike(String s) {
        if (s == null) return false;
        return BizCheckService.DIRECTIVE.matcher(s).find() || BizCheckService.PROCEDURE.matcher(s).find() || BizCheckService.DEFINITION_CALC.matcher(s).find()
                || BizCheckService.DIFFERENCE.matcher(s).find() || BizCheckService.CAUSE_LABEL.matcher(s).find()
                || DEFINITION.matcher(s.strip()).find() || COMBINE.matcher(s).find() || INCLUDE.matcher(s).find();
    }

    /** 작성 단계가 사실 밖에 덧붙이면 안 되는 문장인가(ruleLike + 처리 규칙·용도 + "~해야 한다") */
    static boolean writerRuleLike(String s) {
        if (ruleLike(s) || WRITER_RULE.matcher(s).find()) return true;
        var must = BizCheckService.MUST.matcher(s);
        return must.find() && !BizCheckService.MUST_OK.matcher(s).find();
    }

    /** text 의 문장들 중 사실·예 문장(known)에 해당하지 않으면서 writerRuleLike 인 것 */
    static List<String> addedRules(String text, List<String> known) {
        List<String> knownNorm = known.stream().map(GenTextService::norm).filter(k -> k.length() >= 8).toList();
        List<String> out = new ArrayList<>();
        for (String s : TaskWritingService.sentences(text)) {
            String n = GenTextService.norm(s);
            if (n.length() < 8 || knownNorm.stream().anyMatch(k -> n.contains(k) || k.contains(n))) continue;
            if (writerRuleLike(s)) out.add(s);
        }
        return out;
    }
}
