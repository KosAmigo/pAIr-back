package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.FillerDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 단계 4' 관련 적은 자료(E6·M6)의 입력 만들기와 결과 다듬기. 모델은 StageCallService 로 부른다(호출은 파이프라인이 한다).
 * 입력은 시나리오, 관련 적은 자료 목록(파일 이름, 한 줄 내용), 필요한 묶음의 파일 이름뿐이다. 결함·사실·사례·정답은 주지 않는다(결함을 모르는 단계).
 */
final class FillerService {

    /** BIZ 문서 한 쪽의 글자 수 상한(PDF 한 쪽에 들어가는 900자·30줄보다 넉넉히 작게) */
    static final int PAGE_CHARS = 650;
    static final int PAGE_LINES = 22;

    private FillerService() {}

    /** 단계 입력. perFileChars: 파일 하나가 쓸 글자 수, feedback: 다시 만들 때 앞서 실패한 이유 */
    static String input(BlueprintDto bp, int perFileChars, String feedback) {
        StringBuilder sb = new StringBuilder("[상황]\n").append(bp.scenario()).append("\n\n[만들 파일]\n");
        for (var f : bp.fillers()) sb.append("- ").append(f.path()).append(": ").append(f.summary()).append('\n');
        sb.append("\n[함께 있는 다른 파일의 이름(내용은 모름, 이 파일들을 부르거나 인용하지 않는다)]\n");
        for (String n : bp.neededFiles()) sb.append("- ").append(n).append('\n');
        sb.append("\n[파일 하나의 분량] 한글·코드 합쳐 약 ").append(perFileChars).append("자(이보다 짧으면 안 된다)\n");
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    /** 파일마다 따로 부를 입력들(동시 호출용). 같은 시나리오·필요한 묶음 이름에 파일 하나씩만 만들게 한다. 헷갈리는 자료는 표시한다 */
    static List<String> inputs(BlueprintDto bp, int perFileChars, String feedback) {
        Map<String, Integer> chars = new LinkedHashMap<>();
        bp.fillers().forEach(f -> chars.put(f.path(), perFileChars));
        return inputs(bp, chars, feedback);
    }

    /** 파일마다 분량이 다른 입력들(BIZ: MaterialGateService.bizFillerChars) */
    static List<String> inputs(BlueprintDto bp, Map<String, Integer> charsByPath, String feedback) {
        List<String> out = new ArrayList<>();
        for (var f : bp.fillers()) {
            StringBuilder sb = new StringBuilder("[상황]\n").append(bp.scenario()).append("\n\n[만들 파일: 이 파일 하나만 만든다]\n");
            sb.append("- ").append(f.path()).append(": ").append(f.summary()).append('\n');
            if (f.confusingFlag()) {
                sb.append("\n[헷갈리는 자료] 이 파일은 과제와 같은 영역의 이름·내용으로 실제처럼 그럴듯하게 쓴다(예: 옛 정책, 지난 버전, 비슷한 기능의 다른 클래스). ")
                        .append("다만 어떤 규칙이 맞는지 결론짓지 않고, 필요한 파일의 로직을 대신 설명하거나 정답·판단 근거로 읽힐 문장·값은 쓰지 않는다.\n");
            }
            sb.append("\n[함께 있는 다른 파일의 이름(내용은 모름, 이 파일들을 부르거나 인용하지 않는다)]\n");
            for (String n : bp.neededFiles()) sb.append("- ").append(n).append('\n');
            for (var o : bp.fillers()) if (!o.path().equals(f.path())) sb.append("- ").append(o.path()).append('\n');
            sb.append("\n[파일 하나의 분량] 한글·코드 합쳐 약 ").append(charsByPath.getOrDefault(f.path(), 800)).append("자(이보다 짧으면 안 된다)\n");
            if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
            out.add(sb.toString());
        }
        return out;
    }

    /** 파일별 결과를 하나로 합친다 */
    static FillerDto merge(List<FillerDto> parts) {
        List<DevDraftDto.FileDraft> all = new ArrayList<>();
        for (FillerDto p : parts) if (p != null && p.files() != null) all.addAll(p.files());
        return new FillerDto(all);
    }

    /** 설계도가 정한 경로의 파일만, 비지 않은 것만, 같은 경로는 처음 것만 남긴다 */
    static FillerDto sanitized(FillerDto raw, BlueprintDto bp) {
        Set<String> want = new LinkedHashSet<>();
        bp.fillers().forEach(f -> want.add(f.path()));
        Map<String, DevDraftDto.FileDraft> out = new LinkedHashMap<>();
        if (raw != null && raw.files() != null) {
            for (var f : raw.files()) {
                if (f == null || f.path() == null || f.content() == null || f.content().isBlank()) continue;
                String p = f.path().strip();
                if (want.contains(p)) out.putIfAbsent(p, new DevDraftDto.FileDraft(p, f.content()));
            }
        }
        return new FillerDto(new ArrayList<>(out.values()));
    }

    /** DEV: 관련 적은 자료 파일(경로 → 글) */
    static List<DevDraftDto.FileDraft> devFiles(FillerDto f) {
        return f == null || f.files() == null ? List.of() : f.files();
    }

    /** BIZ: .pdf 는 쪽으로 나눠 문서로 */
    static List<BizDraftDto.DocDraft> bizDocs(FillerDto f) {
        List<BizDraftDto.DocDraft> docs = new ArrayList<>();
        for (var x : devFiles(f)) if (x.path().endsWith(".pdf")) docs.add(new BizDraftDto.DocDraft(x.path(), paginate(x.content())));
        return docs;
    }

    /**
     * BIZ: 관련 적은 문서·CSV 를 뺀 과제(필요한 자료만). 관련 적은 자료는 따로 점검(FILLER 관문)하므로 답 노출·정답 키·일관성·풀이 점검은 이것만 읽는다.
     * 자료 글자의 약 3/4 이 관련 적은 자료라 점검 입력이 크게 준다(2026-11-27 g16: 이 점검들이 비용의 절반)
     */
    static BizDraftDto withoutFillers(BizDraftDto d, BlueprintDto bp) {
        if (d == null || bp == null || bp.fillers() == null || bp.fillers().isEmpty()) return d;
        Set<String> skip = new LinkedHashSet<>();
        bp.fillers().forEach(f -> skip.add(f.path()));
        List<BizDraftDto.DocDraft> docs = d.documents() == null ? null : d.documents().stream().filter(x -> !skip.contains(x.path())).toList();
        List<DevDraftDto.FileDraft> csvs = d.csvFiles() == null ? null : d.csvFiles().stream().filter(x -> !skip.contains(x.path())).toList();
        return new BizDraftDto(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(), d.followUps(), docs, csvs, d.checklist(), d.trap(),
                d.questions(), d.difficulty(), d.competencies(), d.trapKeywords(), d.judgmentPoints(), d.hiddenFlaws());
    }

    /** BIZ: .csv 는 그대로 */
    static List<DevDraftDto.FileDraft> bizCsvs(FillerDto f) {
        return devFiles(f).stream().filter(x -> x.path().endsWith(".csv")).toList();
    }

    /** 글을 문단 단위로 PAGE_CHARS 자·PAGE_LINES 줄 안의 쪽으로 나눈다(문단이 길면 줄 단위로 자른다). 마크다운 기호는 PDF 에 안 맞으니 뺀다 */
    static List<String> paginate(String text) {
        String clean = text.replace("\r", "").replaceAll("(?m)^\\s*#+\\s*", "").replace("**", "").replace("`", "").strip();
        List<String> pages = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int lines = 0;
        for (String para : clean.split("\n\\s*\n")) {
            for (String piece : pieces(para.strip())) {
                int pieceLines = (int) piece.chars().filter(ch -> ch == '\n').count() + 1;
                if (cur.length() > 0 && (cur.length() + piece.length() + 2 > PAGE_CHARS || lines + pieceLines + 1 > PAGE_LINES)) {
                    pages.add(cur.toString().strip());
                    cur.setLength(0);
                    lines = 0;
                }
                if (cur.length() > 0) {
                    cur.append("\n\n");
                    lines++;
                }
                cur.append(piece);
                lines += pieceLines;
            }
        }
        if (cur.length() > 0) pages.add(cur.toString().strip());
        return pages;
    }

    /** 문단이 PAGE_CHARS 보다 길면 줄 단위(줄도 길면 글자 수)로 쪼갠다 */
    private static List<String> pieces(String para) {
        List<String> out = new ArrayList<>();
        if (para.isEmpty()) return out;
        if (para.length() <= PAGE_CHARS - 50) {
            out.add(para);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String line : para.split("\n")) {
            while (line.length() > PAGE_CHARS - 50) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(line.substring(0, PAGE_CHARS - 50));
                line = line.substring(PAGE_CHARS - 50);
            }
            if (cur.length() > 0 && cur.length() + line.length() + 1 > PAGE_CHARS - 50) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
