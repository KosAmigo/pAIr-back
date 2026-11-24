package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 설계도(1단계) 결과를 코드로 다듬고 확인한다. 모델을 부르지 않는다 */
final class BlueprintService {

    private static final Pattern CODE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern CSV_PATH = Pattern.compile("자료/[\\p{L}\\p{N}_ ()\\-]+\\.csv");
    static final String DESCRIPTION = "description";
    static final String NOTES = "notes";

    private BlueprintService() {}

    /**
     * 빈 목록을 채우고, 판단 근거·선택지별 근거·결함 근거 문장이 사실 목록에 없으면 목록에 더한다(M4: 근거는 반드시 글에 들어간다).
     * 자리(place)가 비었거나 없는 곳이면 기본 자리(DEV: description, BIZ: 첫 문서)로 둔다
     */
    static BlueprintDto normalized(BlueprintDto b, boolean biz) {
        List<BlueprintDto.Doc> docs = nz(b.docs());
        Set<String> places = new LinkedHashSet<>();
        places.add(DESCRIPTION);
        if (biz) docs.forEach(d -> places.add(d.path()));
        else places.add(NOTES);
        String fallback = biz && !docs.isEmpty() ? docs.get(0).path() : DESCRIPTION;

        List<BlueprintDto.Fact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var f : nz(b.facts())) {
            if (f == null || f.text() == null || f.text().isBlank() || !seen.add(GenTextService.norm(f.text()))) continue;
            facts.add(new BlueprintDto.Fact(f.text().strip(), places.contains(f.place()) ? f.place() : fallback));
        }
        List<String> required = new ArrayList<>();
        for (var j : nz(b.judgments())) {
            required.add(j.basis());
            for (var o : nz(j.options())) required.add(o.fact());
        }
        for (var f : nz(b.flaws())) required.add(f.evidence());
        for (String r : required) {
            if (r != null && !r.isBlank() && seen.add(GenTextService.norm(r))) facts.add(new BlueprintDto.Fact(r.strip(), fallback));
        }
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), nz(b.competencies()), b.scenario(), nz(b.api()), facts,
                nz(b.examples()), nz(b.flaws()), nz(b.judgments()), nz(b.trapKeywords()), b.conclusion(), nz(b.tables()), nz(b.figures()), docs,
                nz(b.questionPlan()));
    }

    /** 설계도가 다음 단계로 넘어갈 만한지. 문제 문장 목록(없으면 빈 목록). normalized 를 거친 설계도를 받는다 */
    static List<String> problems(BlueprintDto b, boolean biz) {
        List<String> bad = new ArrayList<>();
        if (blank(b.title()) || blank(b.scenario())) bad.add("제목(title)과 상황(scenario)이 있어야 합니다");
        if (b.code() == null || !CODE.matcher(b.code()).matches()) bad.add("code 는 영문 소문자와 하이픈만 쓴 짧은 이름이어야 합니다");
        if (b.facts().size() < 3) bad.add("사실 목록(facts)이 3개 이상 있어야 합니다");
        List<String> rules = b.facts().stream().map(BlueprintDto.Fact::text).filter(RuleSentenceService::ruleLike).toList();
        if (!rules.isEmpty()) {
            bad.add("사실에 정의·공식·규칙 문장이 있습니다. 사실은 기록과 관측(값, 견적·영수증·로그·메모가 말하는 것)만 쓰세요: "
                    + String.join(" / ", rules.subList(0, Math.min(3, rules.size()))));
        }
        if (b.flaws().isEmpty() || b.flaws().stream().anyMatch(f -> blank(f.what()) || blank(f.where()) || blank(f.caughtBy()))) {
            bad.add("숨은 결함(flaws)이 1개 이상 있어야 하고 what·where·caughtBy 가 모두 있어야 합니다");
        }
        if (b.judgments().isEmpty() || b.judgments().stream().anyMatch(j -> blank(j.question()) || blank(j.basis()) || nz(j.options()).size() < 2
                || j.options().stream().anyMatch(o -> blank(o.text()) || blank(o.fact())))) {
            bad.add("판단 지점(judgments)이 1개 이상 있어야 하고 question·basis 와 선택지 2개 이상(text, 근거 fact)이 있어야 합니다");
        }
        if (b.trapKeywords().size() < 2 || b.trapKeywords().size() > 5) bad.add("trapKeywords 는 2~5개여야 합니다");
        if (!biz) {
            if (b.api().isEmpty()) bad.add("공개 API(api)가 있어야 합니다");
            if (b.examples().size() < 2 || b.examples().stream().anyMatch(e -> blank(e.input()) || blank(e.expected()))) bad.add("입력→기대 출력 예(examples)가 2개 이상 있어야 합니다");
            return bad;
        }
        if (b.docs().size() < 2 || b.docs().stream().anyMatch(d -> blank(d.path()) || !d.path().matches("자료/[\\p{L}\\p{N}_ ()\\-]+\\.pdf"))) bad.add("문서 계획(docs)이 2개 이상, 경로는 자료/이름.pdf 여야 합니다");
        if (b.tables().isEmpty()) bad.add("숫자표의 표(tables)가 1개 이상 있어야 합니다");
        for (var t : b.tables()) {
            if (t.path() == null || !CSV_PATH.matcher(t.path()).matches()) bad.add("표 경로는 자료/이름.csv 여야 합니다: " + t.path());
            else if (nz(t.columns()).isEmpty() || nz(t.rows()).isEmpty() || t.rows().stream().anyMatch(r -> r.size() != t.columns().size())) bad.add("표 " + t.path() + " 는 머리글과 데이터 행이 있고 칸 수가 같아야 합니다");
        }
        List<String> paths = new ArrayList<>();
        b.tables().forEach(t -> paths.add(t.path()));
        b.docs().forEach(d -> paths.add(d.path()));
        paths.stream().filter(p -> paths.indexOf(p) != paths.lastIndexOf(p)).distinct().forEach(p -> bad.add("자료 경로가 두 번 있습니다: " + p + " (표와 문서 경로는 모두 달라야 합니다)"));
        if (b.conclusion() == null || blank(b.conclusion().correct()) || blank(b.conclusion().wrong())
                || GenTextService.norm(b.conclusion().correct()).equals(GenTextService.norm(b.conclusion().wrong()))) {
            bad.add("정답 결론과 함정에 빠진 결론(conclusion)이 서로 달라야 합니다");
        }
        if (b.flaws().stream().anyMatch(f -> blank(f.evidence()))) bad.add("숨은 결함마다 근거 문장(evidence)이 있어야 합니다");
        if (b.questionPlan().size() < 3) bad.add("문항 계획(questionPlan)이 3개 이상 있어야 합니다");
        bad.addAll(BizNumbersService.blueprintProblems(b));
        return bad;
    }

    /** 정답 쪽 단계에 줄 설계도: 사실 목록은 이미 응시자 글에 들어 있어 뺀다(토큰 절약) */
    static BlueprintDto withoutFacts(BlueprintDto b) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), List.of(), b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }
}
