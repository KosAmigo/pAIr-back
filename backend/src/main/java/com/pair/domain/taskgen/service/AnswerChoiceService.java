package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 정답 쪽(BIZ) 문항 확인(코드): 보기에 다른 문항의 정답 값이 들어 있지 않고, 판단 지점을 객관식 정답 하나로 만들지 않았는지.
 * 판단 지점은 정답이 하나가 아니므로(E2) 객관식 보기에 선택지가 둘 이상 나란히 들어가면 한쪽만 정답이 되는 문항이다
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
                var other = qs.get(j);
                if (j == i || other == null || other.type() != BizDraftDto.Type.SHORT || other.accepted() == null || other.accepted().isEmpty()) continue;
                Set<String> answer = BizNumbersService.numbers(other.accepted().get(0));
                for (var c : q.choices()) {
                    for (String n : BizNumbersService.numbers(c.content())) {
                        if (n.length() >= 2 && answer.contains(n)) bad.add("Q" + (i + 1) + " 보기 '" + c.content() + "' 에 Q" + (j + 1) + " 의 정답 값 " + n + " 이 있습니다");
                    }
                }
            }
            for (var jp : b.judgments()) {
                Set<String> hit = new LinkedHashSet<>();
                for (var c : q.choices()) {
                    for (var o : jp.options()) {
                        String on = GenTextService.norm(o.text());
                        if (on.length() >= 2 && GenTextService.norm(c.content()).contains(on)) hit.add(on);
                    }
                }
                if (hit.size() >= 2) bad.add("Q" + (i + 1) + " 은 판단 지점('" + jp.question() + "')을 정답 하나인 객관식으로 냈습니다. 판단 지점은 문항으로 묻지 말고 기획안에서 근거로 채점하세요");
            }
        }
        return bad.isEmpty() ? new GenCheckResultDto(TaskPipelineService.CHOICE_CHECK, true, "보기에 다른 문항의 정답 값이 없고 판단 지점을 객관식 하나로 내지 않았습니다.")
                : new GenCheckResultDto(TaskPipelineService.CHOICE_CHECK, false, String.join(" / ", bad));
    }
}
