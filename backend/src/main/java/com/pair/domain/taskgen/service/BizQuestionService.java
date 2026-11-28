package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3단계(BIZ) 문항 글: 입력 만들기, 결과 확인, 정답 쪽과의 조립. 문항 글 단계는 결함을 모른다(설계 3장):
 * 사례의 "구할 값"과 보기 후보만 받고, 기대 결과·근거·결함·함정 결론은 받지 않는다. 정답 보기는 코드가 사례의 기대 결과로 정한다.
 * 객관식 보기는 문항 글 단계가 쓴 것을 쓰지 않고 설계도 보기 후보를 코드가 섞어 그대로 넣는다
 * (2026-11-28 g23: 문항 글 단계가 문장 보기를 "달빛만 선정"처럼 줄여 써서 보기·정답 보기 검사가 다시 만들어도 계속 실패).
 */
final class BizQuestionService {

    static final String CHECK = "문항 글이 설계도의 문항·보기와 같음";
    private static final Pattern NUM = Pattern.compile("(\\d+)");

    private BizQuestionService() {}

    /** 객관식 사례: 보기 후보가 있는 사례 */
    static boolean single(BlueprintDto.Case c) {
        return c.choices() != null && !c.choices().isEmpty();
    }

    static int number(String name) {
        Matcher m = NUM.matcher(name == null ? "" : name);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 판단 지점 사례인가: judgment 표시가 있고 설계도의 판단 지점(judgments)과 1:1 로 이어질 때만(구할 값이 판단 지점의 질문과 같거나, 보기가 그 선택지와 둘 이상 같다).
     * 결론·사실 문항은 judgment 가 true 라고 적혀 있어도 판단 지점으로 보지 않는다
     */
    static boolean isJudgment(BlueprintDto b, BlueprintDto.Case c) {
        if (!c.judgment()) return false;
        for (var j : b.judgments()) {
            if (c.ask() != null && !c.ask().isBlank() && GenTextService.norm(j.question()).equals(GenTextService.norm(c.ask()))) return true;
            long same = j.options().stream().filter(o -> nz(c.choices()).stream().anyMatch(ch -> GenTextService.norm(ch).equals(GenTextService.norm(o.text())))).count();
            if (same >= 2) return true;
        }
        return false;
    }

    /** 사례를 문항 번호 순으로(판단 지점 사례는 문항이 아니라 기획안에서 채점하므로 뺀다) */
    static List<BlueprintDto.Case> ordered(BlueprintDto b) {
        return b.cases().stream().filter(c -> !isJudgment(b, c)).sorted(Comparator.comparingInt(c -> number(c.name()))).toList();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** 객관식 사례별 보기 순서: 설계도 보기 후보를 과제 코드로 정한 순서로 섞는다(같은 설계도면 늘 같은 순서) */
    static java.util.Map<String, List<String>> shuffledChoices(BlueprintDto b) {
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        Random random = new Random(String.valueOf(b.code()).hashCode());
        for (var c : ordered(b)) {
            if (!single(c)) continue;
            List<String> mixed = new ArrayList<>(c.choices());
            Collections.shuffle(mixed, random);
            out.put(c.name(), mixed);
        }
        return out;
    }

    /** 문항 글 단계 입력. 결함·근거·기대 결과·함정 결론은 넣지 않는다. feedback 은 다시 쓸 때 붙는다 */
    static String input(BlueprintDto b, String visibleText, String feedback) {
        StringBuilder sb = new StringBuilder("상황: ").append(b.scenario()).append("\n[응시자에게 보이는 글]\n").append(visibleText).append('\n');
        sb.append("[자료]\n");
        b.docs().forEach(d -> sb.append("- ").append(d.path()).append(" (").append(d.role()).append(")\n"));
        b.tables().forEach(t -> sb.append("- ").append(t.path()).append(" 열: ").append(String.join(", ", t.columns())).append('\n'));
        sb.append("[문항 목록]\n");
        var shuffled = shuffledChoices(b);
        for (var c : ordered(b)) {
            sb.append("- ").append(c.name()).append(" | ");
            if (single(c)) {
                List<String> mixed = shuffled.get(c.name());
                sb.append("객관식 | 구할 값: ").append(c.ask()).append(" | 보기 후보: ").append(String.join(" / ", mixed));
            } else {
                sb.append("단답형(숫자 하나) | 구할 값: ").append(c.ask());
            }
            sb.append('\n');
        }
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    /** 문항 글이 설계도의 문항을 모두 썼는지: id 가 사례와 같고 문구가 있음. 보기는 코드가 설계도 후보로 넣으므로 보지 않는다 */
    static GenCheckResultDto check(BlueprintDto b, BizQuestionsDto q) {
        List<String> bad = new ArrayList<>();
        List<BizQuestionsDto.Question> qs = q == null || q.questions() == null ? List.of() : q.questions();
        for (var c : ordered(b)) {
            var found = qs.stream().filter(x -> x != null && c.name() != null && c.name().equals(x.id())).findFirst().orElse(null);
            if (found == null) {
                bad.add(c.name() + " 문항이 없음");
                continue;
            }
            if (found.content() == null || found.content().isBlank()) bad.add(c.name() + " 문구가 비어 있음");
        }
        long extra = qs.stream().filter(x -> x != null && ordered(b).stream().noneMatch(c -> c.name() != null && c.name().equals(x.id()))).count();
        if (extra > 0) bad.add("설계도에 없는 문항 " + extra + "개");
        return bad.isEmpty() ? new GenCheckResultDto(CHECK, true, "문항 " + ordered(b).size() + "개가 설계도와 같습니다.")
                : new GenCheckResultDto(CHECK, false, String.join(" / ", bad));
    }

    /**
     * 정답 보기 하나를 고른다(없으면 -1). 글이 같은 보기 > 한쪽이 다른 쪽을 담은 보기 중 길이 차가 가장 작은 것 > 숫자만 적힌 보기끼리 같은 값.
     * 숫자 하나가 겹친다고 여러 보기를 정답으로 표시하지 않는다(객관식은 정답 보기가 정확히 1개)
     */
    static int correctIndex(List<String> choices, String expected) {
        String want = GenTextService.norm(expected);
        for (int i = 0; i < choices.size(); i++) if (GenTextService.norm(choices.get(i)).equals(want)) return i;
        int best = -1, bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < choices.size(); i++) {
            String have = GenTextService.norm(choices.get(i));
            if (want.length() >= 4 && (have.contains(want) || want.contains(have)) && Math.abs(have.length() - want.length()) < bestGap) {
                best = i;
                bestGap = Math.abs(have.length() - want.length());
            }
        }
        if (best >= 0) return best;
        if (bare(expected)) for (int i = 0; i < choices.size(); i++) if (bare(choices.get(i)) && ValueService.same(choices.get(i), expected)) return i;
        return -1;
    }

    /** 숫자와 단위 글자만 있는 글(문장이 아님) */
    private static boolean bare(String s) {
        return s != null && ValueService.plain(s).matches("-?\\d+(\\.\\d+)?");
    }

    /**
     * 문항 글(3단계)과 정답 키(4단계)를 합친다. 문항 종류는 사례에서(보기 후보가 있으면 객관식).
     * 기계적 수정: 정답 키의 값은 단위 글자와 쉼표를 떼 숫자만 남기고, 객관식 보기는 설계도 후보를 섞은 것, 정답 보기는 사례의 기대 결과와 가장 같은 보기 하나로 정한다. 판단 지점 사례는 문항으로 만들지 않는다
     */
    static List<BizDraftDto.QuestionDraft> assemble(BlueprintDto b, BizQuestionsDto q, BizAnswerDto a) {
        List<BizDraftDto.QuestionDraft> out = new ArrayList<>();
        List<BizQuestionsDto.Question> texts = q == null || q.questions() == null ? List.of() : q.questions();
        List<BizAnswerDto.Key> keys = a == null || a.keys() == null ? List.of() : a.keys();
        var shuffled = shuffledChoices(b);
        for (var c : ordered(b)) {
            var text = texts.stream().filter(x -> x != null && c.name().equals(x.id())).findFirst().orElse(null);
            var key = keys.stream().filter(x -> x != null && c.name().equals(x.id())).findFirst().orElse(null);
            if (text == null) continue;
            List<BizDraftDto.Source> sources = key == null || key.sources() == null ? List.of() : key.sources();
            if (single(c)) {
                List<String> given = shuffled.get(c.name());
                int right = correctIndex(given, c.expected());
                List<BizDraftDto.Choice> choices = new ArrayList<>();
                for (int i = 0; i < given.size(); i++) choices.add(new BizDraftDto.Choice(given.get(i), i == right));
                out.add(new BizDraftDto.QuestionDraft(BizDraftDto.Type.SINGLE, text.content(), choices, List.of(), "", sources, List.of()));
            } else {
                List<String> accepted = key == null || key.accepted() == null || key.accepted().isEmpty() ? List.of(c.expected()) : key.accepted();
                out.add(new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, text.content(), List.of(), accepted.stream().map(ValueService::plain).toList(),
                        key == null || key.calc() == null ? "" : key.calc(), sources,
                        key == null || key.commonWrongAnswers() == null ? List.of() : key.commonWrongAnswers()));
            }
        }
        return out;
    }
}
