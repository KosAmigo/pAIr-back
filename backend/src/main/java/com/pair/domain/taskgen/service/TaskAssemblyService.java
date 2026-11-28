package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.FillerDto;
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
     */
    static DevDraftDto dev(TaskGenRequest form, BlueprintDto b, DevWritingDto w, DevCodeDto c, DevAnswerDto a) {
        return dev(form, b, w, c, a, null);
    }

    /** filler: 단계 4' 관련 적은 자료(E6). 설계도 fillers 의 경로는 이 단계가 주인이라 시작 코드 단계가 같은 경로를 만들었으면 그것은 버린다 */
    static DevDraftDto dev(TaskGenRequest form, BlueprintDto b, DevWritingDto w, DevCodeDto c, DevAnswerDto a, FillerDto filler) {
        List<String> fillerPaths = FillerService.devFiles(filler).stream().map(DevDraftDto.FileDraft::path).toList();
        List<DevDraftDto.FileDraft> files = new ArrayList<>(unique(nz(c.files()).stream()
                .filter(f -> (startsWith(f, "src/") || startsWith(f, "test/")) && !fillerPaths.contains(f.path())).toList()));
        if (w.notes() != null && !w.notes().isBlank()) files.add(new DevDraftDto.FileDraft("NOTES.md", w.notes()));
        files.addAll(FillerService.devFiles(filler));
        List<DevDraftDto.AltSolution> alts = nz(a.altSolutions()).stream().map(x -> new DevDraftDto.AltSolution(x.note(), unique(x.files()))).toList();
        List<DevDraftDto.WrongFix> wrongs = nz(a.wrongFixes()).stream().map(x -> new DevDraftDto.WrongFix(x.reason(), unique(x.files()))).toList();
        return new DevDraftDto(b.title(), b.summary(), b.code(), b.category(), w.description(), w.requirements(), a.answer(), files,
                unique(nz(a.hiddenTests()).stream().filter(f -> startsWith(f, "test/")).toList()), unique(nz(a.solution()).stream().filter(f -> startsWith(f, "src/")).toList()),
                TaskGenRequest.difficultyOf(form.minutes()), b.competencies(), b.trapKeywords(), wrongs, w.taskFollowUp(), alts, judgments(b), flaws(b, false));
    }

    static BizDraftDto biz(TaskGenRequest form, BlueprintDto b, BizWritingDto w, List<BizDraftDto.QuestionDraft> assembled, BizAnswerDto a) {
        return biz(form, b, w, assembled, a, null);
    }

    /** filler: 단계 4' 관련 적은 자료(E6). 문서는 설계도 문서 계획 뒤에, CSV 는 숫자표 CSV 뒤에 붙는다 */
    static BizDraftDto biz(TaskGenRequest form, BlueprintDto b, BizWritingDto w, List<BizDraftDto.QuestionDraft> assembled, BizAnswerDto a, FillerDto filler) {
        var first = b.flaws().get(0);
        var trap = new BizDraftDto.Trap(first.what(), first.evidence(), b.conclusion().correct(), b.conclusion().wrong());
        String own = w.taskFollowUp() == null || w.taskFollowUp().isBlank() ? GeneratedTaskRepository.DEV_FOLLOW_UPS.get(2) : w.taskFollowUp().strip();
        List<String> followUps = List.of(GeneratedTaskRepository.BIZ_FOLLOW_UPS.get(0), GeneratedTaskRepository.BIZ_FOLLOW_UPS.get(1), own);
        List<BizDraftDto.DocDraft> docs = new ArrayList<>(bizDocs(b, w));
        docs.addAll(FillerService.bizDocs(filler));
        List<DevDraftDto.FileDraft> csvs = new ArrayList<>(BizNumbersService.csvFiles(b));
        csvs.addAll(FillerService.bizCsvs(filler));
        // 문항 근거(sources)는 실제 파일 경로로만. 글자 차이만 나는 경로는 고치고, 그래도 없는 경로는 버린다(저장 때 task_file_id 가 비면 DB 오류).
        // 버린 것은 bizSourceProblems 가 따로 알려 형식 실패로 다시 만들게 한다
        List<String> paths = filePaths(b, docs);
        List<BizDraftDto.QuestionDraft> questions = nz(assembled).stream().map(q -> new BizDraftDto.QuestionDraft(q.type(), q.content(), q.choices(),
                q.accepted(), q.calc(), resolvedSources(q, paths), q.commonWrongAnswers())).toList();
        return new BizDraftDto(b.title(), b.summary(), b.code(), b.category(), w.description(), w.requirements(), followUps, docs,
                csvs, a.checklist(), trap, questions, TaskGenRequest.difficultyOf(form.minutes()), b.competencies(),
                b.trapKeywords(), judgments(b), flaws(b, true));
    }

    /** 문서는 설계도의 문서 계획 순서와 경로대로만(계획에 없거나 같은 경로면 버림) */
    private static List<BizDraftDto.DocDraft> bizDocs(BlueprintDto b, BizWritingDto w) {
        List<BizDraftDto.DocDraft> docs = new ArrayList<>();
        for (var plan : b.docs()) nz(w.documents()).stream().filter(d -> plan.path().equals(d.path())).findFirst().ifPresent(d -> {
            if (docs.stream().noneMatch(x -> x.path().equals(d.path()))) docs.add(d);
        });
        return docs;
    }

    /** 저장될 BIZ 자료 경로(CSV + 문서) */
    private static List<String> filePaths(BlueprintDto b, List<BizDraftDto.DocDraft> docs) {
        List<String> paths = new ArrayList<>();
        BizNumbersService.csvFiles(b).forEach(f -> paths.add(f.path()));
        docs.forEach(d -> paths.add(d.path()));
        return paths;
    }

    /** 근거 경로를 실제 파일 경로로 바꾼다: 같으면 그대로, 띄어쓰기·자료/ 앞부분·확장자 차이만 나면 그 파일, 못 찾으면 null */
    static String resolvePath(String path, List<String> files) {
        if (path == null) return null;
        if (files.contains(path)) return path;
        String key = looseKey(path);
        List<String> hit = files.stream().filter(f -> looseKey(f).equals(key)).toList();
        return hit.size() == 1 ? hit.get(0) : null;
    }

    /** 쪽 번호를 "1,2" 꼴로: 범위("1-2", "1~3")는 풀어 쓰고 "쪽"·"p" 같은 글자는 뺀다. 못 읽으면 그대로 */
    static String normalizePages(String pages) {
        if (pages == null || pages.isBlank()) return pages;
        List<String> out = new ArrayList<>();
        for (String token : pages.replaceAll("(?i)(쪽|페이지|p\\.?)", "").split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            String[] ends = token.split("[-~–]", -1);
            try {
                if (ends.length == 2) {
                    int from = Integer.parseInt(ends[0].strip()), to = Integer.parseInt(ends[1].strip());
                    if (from > to || to - from > 30) return pages;
                    for (int n = from; n <= to; n++) out.add(String.valueOf(n));
                } else out.add(String.valueOf(Integer.parseInt(token.strip())));
            } catch (NumberFormatException e) {
                return pages;
            }
        }
        return String.join(",", out);
    }

    private static String looseKey(String p) {
        return GenTextService.squash(p).replaceFirst("^자료/", "").replaceFirst("\\.(pdf|csv)$", "");
    }

    private static List<BizDraftDto.Source> resolvedSources(BizDraftDto.QuestionDraft q, List<String> paths) {
        List<BizDraftDto.Source> out = new ArrayList<>();
        for (var s : nz(q.sources())) {
            String real = s == null ? null : resolvePath(s.path(), paths);
            if (real != null) out.add(new BizDraftDto.Source(real, normalizePages(s.pages())));
        }
        return out;
    }

    /** 문항 근거 경로 중 실제 자료와 맞출 수 없는 것(버리기 전 기준). 문제가 없으면 빈 목록 */
    static List<String> bizSourceProblems(BlueprintDto b, BizWritingDto w, List<BizDraftDto.QuestionDraft> questions) {
        List<String> paths = filePaths(b, bizDocs(b, w));
        List<String> out = new ArrayList<>();
        int n = 0;
        for (var q : nz(questions)) {
            n++;
            for (var s : nz(q.sources())) {
                if (s == null || resolvePath(s.path(), paths) == null) out.add("Q" + n + " 근거 경로가 실제 자료에 없습니다: " + (s == null ? null : s.path()));
            }
        }
        return out;
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
