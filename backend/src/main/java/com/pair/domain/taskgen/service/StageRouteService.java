package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 실패한 검사를 낸 단계 찾기. 한 검사가 여러 단계를 가리킬 수 있고, 모르면 빈 집합(= 설계도부터 처음부터) */
final class StageRouteService {

    private StageRouteService() {}

    /** 다시 돌릴 단계들과 단계별 실패 검사. 모르는 검사나 설계도 문제가 하나라도 있으면 설계도부터 전부 */
    record Plan(Set<Stage> stages, Map<Stage, List<GenCheckResultDto>> byStage, List<GenCheckResultDto> all) {

        boolean fromBlueprint() {
            return stages.contains(Stage.BLUEPRINT);
        }
    }

    static Plan plan(List<GenCheckResultDto> failed, Function<GenCheckResultDto, Set<Stage>> owner, boolean hasCode) {
        Map<Stage, List<GenCheckResultDto>> by = new LinkedHashMap<>();
        boolean restart = failed.isEmpty();
        for (GenCheckResultDto c : failed) {
            Set<Stage> owners = owner.apply(c);
            if (owners.isEmpty() || owners.contains(Stage.BLUEPRINT)) restart = true;
            owners.forEach(s -> by.computeIfAbsent(s, k -> new ArrayList<>()).add(c));
        }
        Set<Stage> stages = EnumSet.noneOf(Stage.class);
        if (restart) {
            stages.addAll(EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.ANSWER));
            if (hasCode) stages.add(Stage.CODE);
        } else {
            stages.addAll(by.keySet());
            stages.add(Stage.ANSWER); // 앞 단계가 바뀌면 정답 쪽도 다시
        }
        return new Plan(stages, by, failed);
    }

    /** 설계도·시작 코드·정답 쪽 단계에 주는 안내: 실패한 검사의 이름과 상세 */
    static String feedback(List<GenCheckResultDto> failed) {
        if (failed == null || failed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("앞서 만든 결과는 자동 확인에서 아래 검사에 실패했습니다. 이 문제를 고쳐 이 단계를 다시 만들어 주세요.");
        for (GenCheckResultDto c : failed) sb.append("\n- ").append(c.name()).append(": ").append(c.detail());
        return sb.toString();
    }
}
