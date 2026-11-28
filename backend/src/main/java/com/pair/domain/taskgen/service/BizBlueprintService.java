package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.BlueprintService.blank;
import static com.pair.domain.taskgen.service.BlueprintService.nz;

import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BIZ 설계도 형식 검사(2026-11-26 g6: 직군별 설계도). 체크리스트는 TaskPromptService.bizBlueprint 끝의 목록과 1:1 이다.
 * 공통 검사는 BlueprintService.commonProblems. 모델을 부르지 않는다
 */
final class BizBlueprintService {

    private BizBlueprintService() {}

    /** BIZ 형식 검사: 공통 + 문서·표 경로, 결론, 함정 근거, 문항 계획, 문항 보기, 숫자표, 다른 문항 정답 */
    static List<String> problems(BlueprintDto b) {
        List<String> bad = new ArrayList<>(BlueprintService.commonProblems(b));
        List<String> caseNames = b.cases().stream().map(BlueprintDto.Case::name).toList();
        // caughtBy 는 문항 번호나 "결론"(결론 문항)
        if (b.flaws().stream().anyMatch(f -> !blank(f.caughtBy()) && !f.caughtBy().contains("결론") && caseNames.stream().noneMatch(n -> n != null && f.caughtBy().contains(n)))) {
            bad.add("결함의 caughtBy 는 사례(cases)의 name 중 하나여야 합니다");
        }
        if (b.docs().size() < 2 || b.docs().stream().anyMatch(d -> blank(d.path()) || !d.path().matches("자료/[\\p{L}\\p{N}_ ()\\-]+\\.pdf"))) bad.add("문서 계획(docs)이 2개 이상, 경로는 자료/이름.pdf 여야 합니다");
        if (b.tables().isEmpty()) bad.add("숫자표의 표(tables)가 1개 이상 있어야 합니다");
        for (var t : b.tables()) {
            if (t.path() == null || !BlueprintService.CSV_PATH.matcher(t.path()).matches()) bad.add("표 경로는 자료/이름.csv 여야 합니다: " + t.path());
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
        for (var c : b.cases()) {
            if (blank(c.ask())) bad.add("사례 " + c.name() + " 에 구할 값(ask)이 있어야 합니다");
            int n = nz(c.choices()).size();
            boolean judgment = BizQuestionService.isJudgment(b, c);
            // 객관식은 보기 4개(BizCheckService 형식 검사와 같다, g6-biz 06: 3개라 형식에서 떨어짐)
            if (n > 0 && ((judgment ? n < 2 : n != 4) || (!judgment && BizQuestionService.correctIndex(nz(c.choices()), c.expected()) < 0))) {
                bad.add("사례 " + c.name() + " 의 보기(choices)는 정확히 4개이고 정답(expected)과 같은 값이 하나 있어야 합니다(판단 지점은 2개 이상)");
            }
        }
        bad.addAll(BizNumbersService.blueprintProblems(b));
        bad.addAll(optionConflicts(b));
        return bad;
    }

    /** E6·M6 BIZ 목록 검사: 필요한 묶음 개수, 묶음은 문서·표 경로, 관련 적은 자료 꼴, 자료 전체 파일 수 */
    static List<String> materialProblems(BlueprintDto b, int minutes) {
        List<String> bad = new ArrayList<>();
        int[] r = BlueprintService.materialRange(true, minutes);
        List<String> planned = new ArrayList<>(); // 설계도가 이미 정한 파일(문서·표)
        nz(b.docs()).forEach(d -> planned.add(d.path()));
        nz(b.tables()).forEach(t -> planned.add(t.path()));
        BlueprintService.neededCount(bad, b, r, minutes);
        for (String n : nz(b.neededFiles())) if (!planned.contains(n)) bad.add("필요한 묶음 파일이 문서 계획(docs)·표(tables)에 없습니다: " + n);
        BlueprintService.fillerShape(bad, b, planned, p -> p.matches("자료/[\\p{L}\\p{N}_ ()\\-]+\\.(pdf|csv)"), "자료/이름.pdf 또는 자료/이름.csv");
        Set<String> all = new LinkedHashSet<>(nz(b.neededFiles()));
        nz(b.fillers()).forEach(f -> all.add(f.path()));
        all.addAll(planned);
        BlueprintService.fileCount(bad, b, all.size(), r[2], r[3], minutes, "자료 전체(문서·CSV·관련 적은 자료)");
        return bad;
    }

    /** 한 문항의 정답 값이 다른 문항의 보기나 문구(구할 값)에 들어 있으면 그 문항을 풀며 이 문항의 답이 드러난다 */
    static List<String> optionConflicts(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (var ci : nz(b.cases())) {
            if (ci.judgment() || blank(ci.expected())) continue;
            String want = GenTextService.norm(ci.expected());
            Pattern token = Pattern.compile("(?<![0-9.])" + Pattern.quote(ci.expected().strip()) + "(?![0-9.])");
            for (var cj : nz(b.cases())) {
                if (cj == ci || cj.name() == null || cj.name().equals(ci.name())) continue;
                if (nz(cj.choices()).stream().anyMatch(ch -> GenTextService.norm(ch).equals(want) || (ValueService.same(ch, ci.expected())))) {
                    bad.add("사례 " + ci.name() + " 의 정답 값(" + ci.expected() + ")이 사례 " + cj.name() + " 의 보기에 있습니다. 다른 문항의 보기에는 이 값을 쓰지 마세요");
                } else if (ci.expected().strip().length() >= 3 && !blank(cj.ask()) && token.matcher(cj.ask()).find()) {
                    bad.add("사례 " + ci.name() + " 의 정답 값(" + ci.expected() + ")이 사례 " + cj.name() + " 의 구할 값 문구에 있습니다");
                }
            }
        }
        return bad.stream().distinct().toList();
    }
}
