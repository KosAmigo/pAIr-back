package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M6 결정적 검사(기준 문서 pAIr-docs 설계/좋은_과제_기준.md 3장 M6, 6장 과제별 AI 제한). 모델을 부르지 않는다.
 * - 필요한 묶음(설계도 neededFiles)의 글자 수 ≤ 첨부 맥락 한도의 70%: 필요한 묶음만 골라 붙이면 제한 안에서 풀린다. 어기면 설계도를 다시 만든다.
 * - 자료 전체 글자 수 ≥ 한도의 2배: 다 붙일 수 없어 골라야 한다(E6). 어기면 단계 4'(관련 적은 자료)를 다시 만든다.
 * materials 는 응시자가 첨부할 수 있는 파일(경로 → 글)이다. DEV 는 시작 코드·공개 테스트·NOTES.md·관련 적은 자료, BIZ 는 문서 글·CSV.
 */
final class MaterialGateService {

    /** 필요한 묶음은 한도의 70% 이하 */
    static final double NEEDED_MAX_RATIO = 0.7;
    /** 자료 전체는 한도의 2배 이상 */
    static final double TOTAL_MIN_RATIO = 2.0;
    /** 단계 4' 가 노리는 자료 전체(한도의 2.3배): 2배 선에 아슬아슬하게 걸리지 않게 여유를 둔다 */
    static final double TOTAL_TARGET_RATIO = 2.3;

    private MaterialGateService() {}

    /** 파일들의 글자 수 합계(없는 파일은 0) */
    static int chars(Map<String, String> materials, List<String> paths) {
        int n = 0;
        for (String p : paths) n += materials.getOrDefault(p, "").length();
        return n;
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
        int size = chars(materials, needed);
        int max = (int) Math.floor(limits.contextChars() * NEEDED_MAX_RATIO);
        if (size > max) {
            return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, false, "필요한 묶음 " + size + "자가 맥락 한도 " + limits.contextChars() + "자의 70%(" + max
                    + "자)를 넘습니다. 필요한 묶음 파일을 줄이거나 더 작은 파일로 나눠 고르세요. 파일: " + sizes(materials, needed));
        }
        return new GenCheckResultDto(TaskPipelineService.M6_NEEDED_CHECK, true, "필요한 묶음 " + size + "자 ≤ " + max + "자(한도 " + limits.contextChars() + "자의 70%)");
    }

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
