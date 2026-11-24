package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 2단계 작성의 입력 만들기와 결과 확인. 정보 가리기가 이 설계의 핵심이라(설계 3장) 작성 입력은 여기서만 만든다:
 * 사실 목록(결함·판단 근거·배경이 섞인 채 자리별로 섞음), 예 목록, API·문서 계획·CSV 표(모두 응시자에게 보이는 것), 정해야 할 질문만 준다.
 * 결함(what·where·evidence·caughtBy), 판단 선택지와 근거 연결, 함정 핵심어, 결론, 문항 계획은 주지 않는다.
 */
final class TaskWritingService {

    private TaskWritingService() {}

    /** 작성 단계 입력. feedback 은 다시 쓸 때만(writerFeedback 으로 만든, 결함 정보가 없는 글) */
    static String input(BlueprintDto b, boolean biz, int minutes, String feedback) {
        StringBuilder sb = new StringBuilder("제한 시간: ").append(minutes).append("분\n상황: ").append(b.scenario()).append('\n');
        if (!b.api().isEmpty()) sb.append("공개 API:\n").append(lines(b.api()));
        List<String> questions = b.judgments().stream().map(BlueprintDto.Judgment::question).toList();
        if (!questions.isEmpty()) sb.append("응시자가 정해야 할 것(요청 문장에만 쓴다):\n").append(lines(questions));
        if (biz) {
            sb.append("문서 계획:\n").append(lines(b.docs().stream().map(d -> d.path() + " (" + d.role() + ")").toList()));
            for (var t : b.tables()) {
                sb.append("CSV ").append(t.path()).append(":\n").append(String.join(",", t.columns())).append('\n');
                t.rows().forEach(r -> sb.append(String.join(",", r)).append('\n'));
            }
        }
        Random random = new Random(String.valueOf(b.code()).hashCode());
        Map<String, List<String>> byPlace = new LinkedHashMap<>();
        for (var f : b.facts()) byPlace.computeIfAbsent(f.place(), k -> new ArrayList<>()).add(f.text());
        byPlace.forEach((place, texts) -> {
            List<String> mixed = new ArrayList<>(texts);
            Collections.shuffle(mixed, random);
            sb.append("사실 목록 [place=").append(place).append("]:\n").append(lines(mixed));
        });
        if (!b.examples().isEmpty()) {
            List<String> mixed = new ArrayList<>(b.examples().stream().map(e -> e.input() + " → " + e.expected()).toList());
            Collections.shuffle(mixed, random);
            sb.append("예 목록:\n").append(lines(mixed));
        }
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    private static String lines(List<String> l) {
        StringBuilder sb = new StringBuilder();
        l.forEach(s -> sb.append("- ").append(s).append('\n'));
        return sb.toString();
    }

    /** 사실 목록의 문장과 예의 입력·기대 출력이 글에 그대로 들어갔는지(M4). examplesText 는 요구사항(DEV)에서 찾는다 */
    static GenCheckResultDto factsCheck(BlueprintDto b, String corpus, String examplesText) {
        String all = GenTextService.norm(corpus);
        String ex = GenTextService.norm(examplesText);
        List<String> missing = new ArrayList<>();
        for (var f : b.facts()) if (!all.contains(GenTextService.norm(f.text()))) missing.add(f.text());
        for (var e : b.examples()) {
            if (!ex.contains(GenTextService.norm(e.input())) || !ex.contains(GenTextService.norm(e.expected()))) missing.add(e.input() + " → " + e.expected());
        }
        if (missing.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FACTS_CHECK, true, "사실 " + b.facts().size() + "개와 예 " + b.examples().size() + "개가 글에 그대로 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.FACTS_CHECK, false, "글에 그대로 들어가지 않은 사실·예가 있습니다: " + String.join(" / ", missing.subList(0, Math.min(5, missing.size()))));
    }

    /** 작성 단계가 사실·예 밖에 덧붙인 규칙·정의·용도 문장이 없는지(M5, E2). 글 전체(설명·요구사항·메모·문서)를 문장 단위로 본다 */
    static GenCheckResultDto rulesCheck(BlueprintDto b, String corpus) {
        List<String> known = new ArrayList<>(b.facts().stream().map(BlueprintDto.Fact::text).toList());
        b.examples().forEach(e -> {
            known.add(e.input());
            known.add(e.input() + " → " + e.expected());
        });
        List<String> added = RuleSentenceService.addedRules(corpus, known);
        if (added.isEmpty()) return new GenCheckResultDto(TaskPipelineService.RULES_CHECK, true, "사실·예 밖에 덧붙인 규칙·정의 문장이 없습니다.");
        return new GenCheckResultDto(TaskPipelineService.RULES_CHECK, false, "사실·예에 없는 규칙·정의·용도 문장이 있습니다: " + String.join(" / ", added.subList(0, Math.min(5, added.size()))));
    }

    /**
     * 작성 단계를 다시 돌릴 때 붙이는 안내. 검사 상세에는 결함·핵심어가 들어 있을 수 있어 그대로 주지 않는다:
     * 검사 이름에 맞는 일반 규칙과, 작성 단계가 이전에 쓴 글 중 상세에 인용된 문장만 준다.
     */
    static String writerFeedback(List<GenCheckResultDto> failed, String previousText) {
        StringBuilder sb = new StringBuilder("앞서 쓴 글이 확인에서 아래 문제로 걸렸습니다. 같은 사실 목록으로 처음부터 다시 쓰세요.\n");
        for (GenCheckResultDto c : failed) {
            sb.append("- ").append(c.name()).append(": ").append(rule(c.name())).append('\n');
            String detail = GenTextService.norm(c.detail());
            int shown = 0;
            for (String s : sentences(previousText)) {
                String n = GenTextService.norm(s);
                if (n.length() >= 10 && detail.contains(n) && shown++ < 5) sb.append("  고칠 문장: ").append(s).append('\n');
            }
        }
        return sb.toString();
    }

    private static String rule(String check) {
        if (check.equals(TaskPipelineService.FACTS_CHECK)) return "사실 목록의 문장과 예를 하나도 빼지 말고 그대로 넣는다.";
        if (check.equals(TaskPipelineService.RULES_CHECK)) return "사실·예 밖에는 상황, 인물, 업무 흐름 같은 배경만 쓰고, 규칙·조건·정의·용도 문장은 쓰지 않는다.";
        if (check.equals(BizNumbersService.NAME)) return "문서에는 사실 문장과 CSV 의 숫자만 쓴다.";
        if (check.equals(AnswerLeakJudgeService.NAME) || check.equals(AnswerLeakJudgeService.NUMBERS)) return "원인, 고치는 법, 계산 순서를 알려 주는 문장을 빼고 사실과 예만 쓴다. 숫자는 서로 맞게 쓴다.";
        if (check.equals(DevCheckService.TRAP_WORDS) || check.equals(BizCheckService.TRAP_WORDS)) return "사실 목록에 없는 말로 원인이나 결함을 짚지 않는다.";
        if (check.equals(BizCheckService.EVIDENCE_STYLE) || check.equals(BizCheckService.NO_PROCEDURE)) return "정의는 무엇을 세는지까지만 쓰고 계산 방법, 해석 지시, 두 대상이 다르다는 문장은 쓰지 않는다.";
        if (check.equals(BizCheckService.LEAK)) return "설명과 요구사항에 문항의 답이 되는 숫자를 쓰지 않는다.";
        if (check.equals(DevCheckService.MATERIALS)) return "notes 에 현상과 재현 값을 200자 이상 쓴다.";
        return "이 확인을 통과하도록 쓴다.";
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text == null ? new String[0] : text.split("\\R")) {
            for (String s : line.split("(?<=[.!?。])\\s+")) if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }
}
