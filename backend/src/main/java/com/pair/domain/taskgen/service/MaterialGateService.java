package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M6 결정적 검사(기준 문서 pAIr-docs 설계/좋은_과제_기준.md 3장 M6, 6장 과제별 AI 제한). 모델을 부르지 않는다.
 * - 필요한 묶음(설계도 neededFiles, 공개 테스트 뺌)의 글자 수 ≤ 첨부 맥락 한도의 70%: 필요한 묶음만 골라 붙이면 제한 안에서 풀린다. 어기면 설계도를 다시 만든다.
 * - 자료 전체 글자 수 ≥ 한도의 2배: 다 붙일 수 없어 골라야 한다(E6). 어기면 단계 4'(관련 적은 자료)를 다시 만든다.
 * materials 는 응시자가 첨부할 수 있는 파일(경로 → 글)이다. DEV 는 시작 코드·공개 테스트·NOTES.md·관련 적은 자료, BIZ 는 문서 글·CSV.
 */
final class MaterialGateService {

    /** 필요한 묶음은 한도의 40~70%: 너무 작으면 앞에서부터 붙이거나 이름만 보고 골라도 풀린다 */
    static final double NEEDED_MIN_RATIO = 0.4;
    static final double NEEDED_MAX_RATIO = 0.7;
    /** 필요한 묶음이 노리는 크기(한도의 55%): 40~70% 가운데 */
    static final double NEEDED_TARGET_RATIO = 0.55;
    /** 헷갈리는 자료는 관련 적은 자료의 1/3 이상 */
    static final double CONFUSING_MIN_SHARE = 1.0 / 3;
    /** 자료 전체는 한도의 2배 이상 */
    static final double TOTAL_MIN_RATIO = 2.0;
    /** 단계 4' 가 노리는 자료 전체(한도의 2.3배): 2배 선에 아슬아슬하게 걸리지 않게 여유를 둔다 */
    static final double TOTAL_TARGET_RATIO = 2.3;

    private MaterialGateService() {}

    /** 관련 적은 자료 n개 중 헷갈리는 자료로 표시해야 하는 최소 개수(1/3 올림) */
    static int confusingMin(int fillerCount) {
        return (int) Math.ceil(fillerCount * CONFUSING_MIN_SHARE - 1e-9);
    }

    /** 파일들의 글자 수 합계(없는 파일은 0) */
    static int chars(Map<String, String> materials, List<String> paths) {
        int n = 0;
        for (String p : paths) n += materials.getOrDefault(p, "").length();
        return n;
    }

    /**
     * 필요한 묶음 크기에 세는 파일: 공개 테스트(test/…)는 뺀다(2026-11-26 g6 판정: 관문은 넣고 판정은 빼고 세서 어긋남).
     * 공개 테스트는 응시자가 실행해 보는 파일이라 첨부 없이도 결과를 볼 수 있다
     */
    static List<String> bundle(List<String> needed) {
        return needed == null ? List.of() : needed.stream().filter(p -> p != null && !p.startsWith("test/")).toList();
    }

    /** 필요한 묶음 크기(공개 테스트 뺌) */
    static int bundleChars(Map<String, String> materials, List<String> needed) {
        return chars(materials, bundle(needed));
    }

    static int totalChars(Map<String, String> materials) {
        return materials.values().stream().mapToInt(String::length).sum();
    }

    /** 필요한 묶음 검사: 파일이 모두 있고 합계가 맥락 한도의 70% 이하 */
    static GenCheckResultDto needed(BlueprintDto bp, Map<String, String> materials, AiLimitsDto limits) {
        List<String> needed = bp.neededFiles() == null ? List.of() : bp.neededFiles();
        if (needed.isEmpty()) return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, false, "설계도에 필요한 자료 묶음(neededFiles)이 없습니다");
        List<String> missing = needed.stream().filter(p -> !materials.containsKey(p)).toList();
        if (!missing.isEmpty()) {
            return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, false, "필요한 묶음 파일이 만들어지지 않았습니다: " + missing);
        }
        int size = bundleChars(materials, needed);
        int max = (int) Math.floor(limits.contextChars() * NEEDED_MAX_RATIO);
        if (size > max) {
            return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, false, "필요한 묶음 " + size + "자가 맥락 한도 " + limits.contextChars() + "자의 70%(" + max
                    + "자)를 넘습니다. 필요한 묶음 파일을 줄이거나 더 작은 파일로 나눠 고르세요(공개 테스트는 세지 않음). 파일: " + sizes(materials, bundle(needed)));
        }
        // 통과해도 파일 목록을 남긴다: 판정(검수)이 어느 파일이 묶음인지 몰라 추측하다 틀렸다(2026-11-27 g15 04·05·06)
        return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, true, "필요한 묶음 " + size + "자 ≤ " + max + "자(한도 " + limits.contextChars() + "자의 70%). 파일: " + sizes(materials, bundle(needed)));
    }

    /**
     * 필요한 묶음이 한도의 40% 이상인가(M6, 경고만: 다시 만들지 않는다). 너무 작으면 앞의 파일만 붙이거나 이름만 보고 골라도 풀린다.
     * 파일이 없거나 70% 초과는 needed() 가 실패로 잡으므로 여기서는 크기만 본다
     */
    static GenCheckResultDto neededMin(BlueprintDto bp, Map<String, String> materials, AiLimitsDto limits) {
        List<String> needed = bp.neededFiles() == null ? List.of() : bp.neededFiles();
        int size = bundleChars(materials, needed);
        int min = (int) Math.ceil(limits.contextChars() * NEEDED_MIN_RATIO);
        if (size < min) {
            return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_MIN_CHECK, false, "필요한 묶음 " + size + "자가 맥락 한도 " + limits.contextChars() + "자의 40%(" + min
                    + "자)에 못 미칩니다. 너무 작으면 앞의 파일만 붙이거나 이름만 보고 골라도 풀립니다(공개 테스트는 세지 않음). 파일: " + sizes(materials, bundle(needed)), GenCheckResultDto.WARN);
        }
        return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_MIN_CHECK, true, "필요한 묶음 " + size + "자 ≥ " + min + "자(한도의 40%)", GenCheckResultDto.WARN);
    }

    /** 헷갈리는 자료가 관련 적은 자료의 1/3 이상인가(M6, 경고만) */
    static GenCheckResultDto confusing(BlueprintDto bp) {
        var fillers = bp.fillers() == null ? List.<BlueprintDto.Filler>of() : bp.fillers();
        long n = fillers.stream().filter(BlueprintDto.Filler::confusingFlag).count();
        int need = confusingMin(fillers.size());
        if (n < need) {
            return new GenCheckResultDto(TaskPipelineService.M6_CONFUSING_CHECK, false, "관련 적은 자료 " + fillers.size() + "개 중 헷갈리는 자료가 " + n + "개입니다(1/3 이상, " + need + "개 이상이어야 함)",
                    GenCheckResultDto.WARN);
        }
        return new GenCheckResultDto(TaskPipelineService.M6_CONFUSING_CHECK, true, "헷갈리는 자료 " + n + "/" + fillers.size() + "개(1/3 이상)", GenCheckResultDto.WARN);
    }

    /**
     * 결함 근거가 필요한 파일 2개 이상에 나뉘어 있는가(M6, 경고만). 글을 찾지 않고 설계도 구조로 센다:
     * 결함 위치(flaws.where)가 가리키는 필요한 파일(DEV 는 결함이 있는 코드 파일, BIZ 는 문서 제목·경로)과
     * 사례의 자료 근거(cases.basis 와 같은 사실 facts 의 place: DEV 는 NOTES.md, BIZ 는 문서 경로)를 합쳐 서로 다른 필요한 파일의 수
     */
    static GenCheckResultDto evidence(BlueprintDto bp) {
        List<String> needed = bp.neededFiles() == null ? List.of() : bp.neededFiles();
        java.util.Set<String> files = new java.util.LinkedHashSet<>();
        if (bp.flaws() != null) {
            for (var f : bp.flaws()) {
                String where = f.where() == null ? "" : f.where();
                for (String p : needed) {
                    String base = p.substring(p.lastIndexOf('/') + 1);
                    String stem = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base; // 클래스 이름("CouponIssuer.issue" 처럼 쓰인다)
                    if (where.contains(p) || (stem.length() > 3 && where.contains(stem))) files.add(p);
                }
                if (bp.docs() != null) {
                    for (var d : bp.docs()) {
                        if (needed.contains(d.path()) && d.role() != null && !d.role().isBlank() && where.contains(d.role())) files.add(d.path());
                    }
                }
            }
        }
        if (bp.cases() != null && bp.facts() != null) {
            for (var c : bp.cases()) {
                if (c.judgment() || c.basis() == null) continue;
                String b = GenTextService.norm(c.basis());
                for (var fact : bp.facts()) {
                    String place = BlueprintService.NOTES.equals(fact.place()) ? NOTES_FILE : fact.place(); // DEV 사실 자리 notes 는 NOTES.md 파일이 된다
                    if (fact.text() != null && GenTextService.norm(fact.text()).equals(b) && place != null && needed.contains(place)) files.add(place);
                }
            }
        }
        if (files.size() < MIN_EVIDENCE_FILES) {
            return new GenCheckResultDto(TaskPipelineService.M6_EVIDENCE_CHECK, false, "결함 위치와 사례 근거가 가리키는 필요한 파일이 " + files.size() + "개(" + files
                    + ")입니다. 2개 이상이어야 파일 하나만 붙여서는 못 풉니다. 필요한 파일: " + needed, GenCheckResultDto.WARN);
        }
        return new GenCheckResultDto(TaskPipelineService.M6_EVIDENCE_CHECK, true, "결함 위치·사례 근거가 필요한 파일 " + files.size() + "개에 나뉨: " + files, GenCheckResultDto.WARN);
    }

    /** 근거가 들어 있어야 하는 필요한 파일의 최소 개수 */
    static final int MIN_EVIDENCE_FILES = 2;

    /** DEV 접수 메모 파일 이름(사실 자리 notes 가 이 파일이 된다) */
    static final String NOTES_FILE = "NOTES.md";

    /** 자료 전체 검사: 관련 적은 자료가 모두 있고 전체 합계가 맥락 한도의 2배 이상 */
    static GenCheckResultDto total(BlueprintDto bp, Map<String, String> materials, AiLimitsDto limits) {
        int min = (int) Math.ceil(limits.contextChars() * TOTAL_MIN_RATIO);
        List<String> fillerPaths = bp.fillers() == null ? List.of() : bp.fillers().stream().map(BlueprintDto.Filler::path).toList();
        List<String> missing = fillerPaths.stream().filter(p -> !materials.containsKey(p)).toList();
        int size = totalChars(materials);
        if (!missing.isEmpty() || size < min) {
            StringBuilder sb = new StringBuilder("자료 전체 ").append(size).append("자가 맥락 한도 ").append(limits.contextChars()).append("자의 2배(").append(min).append("자)에 못 미칩니다");
            if (size >= min) sb = new StringBuilder("자료 전체는 충분하지만 관련 적은 자료 파일이 빠졌습니다");
            if (!missing.isEmpty()) sb.append(". 만들어지지 않은 파일: ").append(missing);
            sb.append(". 관련 적은 자료를 더 길게 써서 합계가 ").append(min).append("자 이상이 되게 하세요(부족 약 ").append(Math.max(0, min - size)).append("자). 파일 글자 수: ")
                    .append(sizes(materials, new ArrayList<>(materials.keySet())));
            return new GenCheckResultDto(TaskPipelineService.M6_TOTAL_CHECK, false, sb.toString());
        }
        return new GenCheckResultDto(TaskPipelineService.M6_TOTAL_CHECK, true, "자료 전체 " + size + "자 ≥ " + min + "자(한도 " + limits.contextChars() + "자의 2배)");
    }

    /**
     * 관련 적은 자료 파일 하나가 쓸 글자 수(단계 4' 입력): 자료 전체가 한도의 2.3배가 되도록 필요한 묶음 분량을 뺀 만큼을 관련 적은 자료로 나눈다.
     * neededChars 는 필요한 묶음의 예상 글자 수(처음엔 한도의 50%로 어림, 다시 만들 때는 실제 값)
     */
    static int perFillerChars(BlueprintDto bp, int neededChars, AiLimitsDto limits) {
        int count = Math.max(1, bp.fillers() == null ? 1 : bp.fillers().size());
        int target = (int) Math.ceil(limits.contextChars() * TOTAL_TARGET_RATIO);
        int others = Math.max(0, target - neededChars);
        return Math.max(800, (int) Math.ceil(others / (double) count / 100.0) * 100);
    }

    private static String sizes(Map<String, String> materials, List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p).append(' ').append(materials.getOrDefault(p, "").length()).append('자');
        }
        return sb.toString();
    }
}
