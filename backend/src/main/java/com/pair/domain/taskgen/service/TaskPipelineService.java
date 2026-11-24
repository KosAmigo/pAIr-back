package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 과제 1개를 만드는 방법(설계 pAIr-docs 설계/과제_생성_파이프라인.md 3장). 단계별 생성은 Dev/BizPipelineService 가 하고,
 * 옛 방식(한 번에 과제 전체)은 TaskGeneratorService 안의 OneShotRun 이 같은 모양으로 한다.
 * start 는 과제 1개마다 상태(단계 결과)를 쥔 Run 을 돌려주므로 실패한 단계부터 다시 돌릴 수 있다.
 */
public interface TaskPipelineService<D> {

    /** 1 설계도, 2 작성, 2' 시작 코드(DEV), 3 정답 쪽. 순서대로 앞 단계 결과가 뒤 단계의 입력이다 */
    enum Stage { BLUEPRINT, WRITING, CODE, ANSWER }

    String STEP_BLUEPRINT = "설계도 작성 중";
    String STEP_WRITING = "글 작성 중";
    String STEP_CODE = "시작 코드 작성 중";
    String STEP_ANSWER = "정답 쪽 작성 중";

    /** 코드가 단계 결과를 보고 확인하는 검사 이름(실행·계산 검사와 같이 results 에 들어간다) */
    String BLUEPRINT_CHECK = "설계도 형식"; // 설계도 단계
    String FACTS_CHECK = "사실·예가 글에 그대로 들어감"; // 작성 단계(M4: 판단 근거와 결함 근거가 자료에 있다)

    String RULES_CHECK = "작성 글에 규칙·정의 없음"; // 작성 단계가 사실 밖에 규칙·정의·용도 문장을 덧붙이지 않음(M5, E2)
    String CHOICE_CHECK = "보기에 다른 문항 정답·판단 지점 객관식 없음"; // 정답 쪽 단계(BIZ)
    String PATHS_CHECK = "파일 경로 중복 없음"; // 조립 단계의 안전장치: 같은 경로가 겹치면 실패(형식 문제)

    Run<D> start(TaskGenRequest form);

    interface Run<D> {

        /** 1단계부터 끝까지 만든다 */
        D create(Consumer<String> onStep);

        /** 실패한 검사를 낸 단계부터 다시 만든다. 단계를 정하지 못하면 설계도부터 처음부터 */
        D redo(List<GenCheckResultDto> failed, Consumer<String> onStep);

        /** 지금 Draft 에 대해 코드가 단계 결과로 확인한 검사 결과 */
        List<GenCheckResultDto> checks();

        /** 지금까지 부른 단계 호출의 토큰 기록 */
        List<StageUsageDto> usage();

        /** 마지막 redo 가 다시 돌린 단계 이름들(없으면 빈 목록) */
        List<String> redone();
    }
}
