package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 조립: 단계 결과를 기존 DevDraftDto·BizDraftDto 로 합친다. 저장과 실행·계산 검사는 그대로 이 Draft 를 쓴다. 모델을 부르지 않는다 */
final class TaskAssemblyService {

    private TaskAssemblyService() {}

    /**
     * 파일 주인(같은 경로를 두 단계가 만들지 않게): 시작 코드 단계는 src/·test/ 만, NOTES.md 는 작성 단계만,
     * 숨은 테스트(test/)·정답(src/)·다른 풀이·오답은 정답 쪽 단계만, CSV 는 숫자표에서 코드만, 문서는 설계도의 문서 계획 경로만.
     * 주인이 아닌 단계가 만든 파일은 버리고, 같은 경로가 남으면 앞의 것만 둔다(저장 때 task_files 경로 중복 방지).
     * 겹침은 devPathProblems·bizPathProblems 가 따로 알려 형식 검사 실패로 다시 만들게 한다
     */
    static DevDraftDto dev(TaskGenRequest form, BlueprintDto b, DevWritingDto w, DevCodeDto c, DevAnswerDto a) {
        List<DevDraftDto.FileDraft> files = new ArrayList<>(unique(nz(c.files()).stream().filter(f -> startsWith(f, "src/") || startsWith(f, "test/")).toList()));
        if (w.notes() != null && !w.notes().isBlank()) files.add(new DevDraftDto.FileDraft("NOTES.md", w.notes()));
        List<DevDraftDto.AltSolution> alts = nz(a.altSolutions()).stream().map(x -> new DevDraftDto.AltSolution(x.note(), unique(x.files()))).toList();
        List<DevDraftDto.WrongFix> wrongs = nz(a.wrongFixes()).stream().map(x -> new DevDraftDto.WrongFix(x.reason(), unique(x.files()))).toList();
        return new DevDraftDto(b.title(), b.summary(), b.code(), b.category(), w.description(), w.requirements(), a.answer(), files,
                unique(nz(a.hiddenTests()).stream().filter(f -> startsWith(f, "test/")).toList()), unique(nz(a.solution()).stream().filter(f -> startsWith(f, "src/")).toList()),
                TaskGenRequest.difficultyOf(form.minutes()), b.competencies(), b.trapKeywords(), wrongs, a.taskFollowUp(), alts, judgments(b), flaws(b, false));
    }

    static BizDraftDto biz(TaskGenRequest form, BlueprintDto b, BizWritingDto w, BizAnswerDto a) {
        var first = b.flaws().get(0);
        var trap = new BizDraftDto.Trap(first.what(), first.evidence(), b.conclusion().correct(), b.conclusion().wrong());
        String own = a.taskFollowUp() == null || a.taskFollowUp().isBlank() ? GeneratedTaskRepository.DEV_FOLLOW_UPS.get(2) : a.taskFollowUp().strip();
        List<String> followUps = List.of(GeneratedTaskRepository.DEV_FOLLOW_UPS.get(0), GeneratedTaskRepository.DEV_FOLLOW_UPS.get(1), own);
        // 문서는 설계도의 문서 계획 순서와 경로대로만(계획에 없거나 같은 경로면 버림)
        List<BizDraftDto.DocDraft> docs = new ArrayList<>();
        for (var plan : b.docs()) nz(w.documents()).stream().filter(d -> plan.path().equals(d.path())).findFirst().ifPresent(d -> {
            if (docs.stream().noneMatch(x -> x.path().equals(d.path()))) docs.add(d);
        });
        return new BizDraftDto(b.title(), b.summary(), b.code(), b.category(), w.description(), w.requirements(), followUps, docs,
                BizNumbersService.csvFiles(b), a.checklist(), trap, a.questions(), TaskGenRequest.difficultyOf(form.minutes()), b.competencies(),
                b.trapKeywords(), judgments(b), flaws(b, true));
    }

    /** 같은 경로가 겹치는 곳(버리기 전의 단계 결과 기준). 문제가 없으면 빈 목록. 괄호 안은 겹친 곳 */
    static List<String> devPathProblems(DevCodeDto c, DevAnswerDto a) {
        List<String> out = new ArrayList<>();
        dups(nz(c.files()).stream().filter(f -> startsWith(f, "src/") || startsWith(f, "test/")).toList(), "시작 코드", out);
        dups(nz(a.hiddenTests()), "숨은 테스트", out);
        dups(nz(a.solution()), "정답", out);
        int n = 0;
        for (var x : nz(a.altSolutions())) dups(nz(x.files()), "다른 풀이 " + ++n, out);
        n = 0;
        for (var x : nz(a.wrongFixes())) dups(nz(x.files()), "오답 " + ++n, out);
        Set<String> pub = new LinkedHashSet<>();
        nz(c.files()).forEach(f -> pub.add(f.path()));
        for (var f : nz(a.hiddenTests())) if (pub.contains(f.path())) out.add("같은 경로가 두 번 있습니다: " + f.path() + " (숨은 테스트가 공개 테스트와 같은 경로)");
        return out;
    }

    /** BIZ: 작성 단계의 문서 경로가 겹치거나 문서 계획에 없는 경우 */
    static List<String> bizPathProblems(BlueprintDto b, BizWritingDto w) {
        List<String> out = new ArrayList<>();
        Set<String> plan = new LinkedHashSet<>();
        b.docs().forEach(d -> plan.add(d.path()));
        Set<String> seen = new LinkedHashSet<>();
        for (var d : nz(w.documents())) {
            if (!seen.add(d.path())) out.add("같은 경로가 두 번 있습니다: " + d.path() + " (문서)");
            else if (!plan.contains(d.path())) out.add("문서 계획에 없는 경로입니다: " + d.path() + " (문서)");
        }
        return out;
    }

    private static void dups(List<DevDraftDto.FileDraft> files, String where, List<String> out) {
        Set<String> seen = new LinkedHashSet<>();
        for (var f : files) {
            if (f != null && !seen.add(f.path())) out.add("같은 경로가 두 번 있습니다: " + f.path() + " (" + where + ")");
        }
    }

    private static boolean startsWith(DevDraftDto.FileDraft f, String prefix) {
        return f != null && f.path() != null && f.path().startsWith(prefix);
    }

    private static List<DevDraftDto.FileDraft> unique(List<DevDraftDto.FileDraft> files) {
        Set<String> seen = new LinkedHashSet<>();
        List<DevDraftDto.FileDraft> out = new ArrayList<>();
        for (var f : nz(files)) if (f != null && seen.add(String.valueOf(f.path()))) out.add(f);
        return out;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** 판단 지점: 선택지별 근거 사실이 evidence(글에 그대로 들어가 있음, M4) */
    private static List<DevDraftDto.JudgmentPoint> judgments(BlueprintDto b) {
        return b.judgments().stream().map(j -> new DevDraftDto.JudgmentPoint(j.question(), j.options().stream().map(BlueprintDto.Option::text).toList(), j.basis(),
                new ArrayList<>(new LinkedHashSet<>(j.options().stream().map(BlueprintDto.Option::fact).toList())))).toList();
    }

    private static List<DevDraftDto.HiddenFlaw> flaws(BlueprintDto b, boolean biz) {
        return b.flaws().stream().map(f -> new DevDraftDto.HiddenFlaw(biz ? f.what() : f.what() + " (" + f.where() + ")", biz ? f.where() : f.evidence() == null || f.evidence().isBlank() ? "숨은 테스트 " + f.caughtBy() : f.evidence(), f.caughtBy())).toList();
    }
}
