package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 정답 쪽(BIZ) 문항 확인(코드): 보기에 다른 문항의 정답 값이 들어 있지 않은지(결정적 검사, 단위를 맞춰 0.5 와 50퍼센트를 같은 값으로 본다). 정답 키의 맞고 틀림은 관문의 정답 키 점검이 본다
 */
final class AnswerChoiceService {

    private AnswerChoiceService() {}

    static GenCheckResultDto check(BlueprintDto b, List<BizDraftDto.QuestionDraft> questions) {
        List<String> bad = new ArrayList<>();
        List<BizDraftDto.QuestionDraft> qs = questions == null ? List.of() : questions;
        for (int i = 0; i < qs.size(); i++) {
            var q = qs.get(i);
            if (q == null || q.type() != BizDraftDto.Type.SINGLE || q.choices() == null) continue;
            for (int j = 0; j < qs.size(); j++) {
                if (j == i) continue;
                Set<BigDecimal> answer = answerValues(qs.get(j));
                for (var c : q.choices()) {
                    for (BigDecimal v : ValueService.values(c.content())) {
                        if (!ValueService.trivial(v) && answer.contains(v)) bad.add("Q" + (i + 1) + " 보기 '" + c.content() + "' 에 Q" + (j + 1) + " 의 정답 값 " + v.toPlainString() + " 이 있습니다(단위를 맞춰 비교)");
                    }
                }
            }
        }
        return bad.isEmpty() ? new GenCheckResultDto(TaskPipelineService.CHOICE_CHECK, true, "보기에 다른 문항의 정답 값이 없습니다.")
                : new GenCheckResultDto(TaskPipelineService.CHOICE_CHECK, false, String.join(" / ", bad));
    }

    /** 문항의 정답 값: 단답형은 인정 답, 객관식은 정답 보기(판단 지점 문항은 모든 보기가 정답이라 값으로 보지 않는다) */
    private static Set<BigDecimal> answerValues(BizDraftDto.QuestionDraft q) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (q == null) return out;
        if (q.type() == BizDraftDto.Type.SHORT) {
            if (q.accepted() != null) q.accepted().forEach(a -> out.addAll(ValueService.values(a)));
        } else if (q.choices() != null) {
            long right = q.choices().stream().filter(BizDraftDto.Choice::correct).count();
            if (right == 1) q.choices().stream().filter(BizDraftDto.Choice::correct).forEach(c -> out.addAll(ValueService.values(c.content())));
        }
        return out;
    }
}
