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

    /** 1 설계도, 2 작성, 2' 시작 코드(DEV), 3 문항 글(BIZ), 4 정답 쪽, 4' 관련 적은 자료(FILLER, 싼 모델, E6), REVIEW·REVIEW2·FILLER_REVIEW 관문 검사(직군마다 생성과 다른 회사 모델). 순서대로 앞 단계 결과가 뒤 단계의 입력이다 */
    enum Stage { BLUEPRINT, WRITING, CODE, QUESTIONS, ANSWER, FILLER, REVIEW, REVIEW2, FILLER_REVIEW }

    String STEP_BLUEPRINT = "설계도 작성 중";
    String STEP_WRITING = "글 작성 중";
    String STEP_CODE = "시작 코드 작성 중";
    String STEP_QUESTIONS = "문항 글 작성 중";
    String STEP_ANSWER = "정답 쪽 작성 중";
    String STEP_FILLER = "관련 적은 자료 작성 중";

    /** 코드가 단계 결과를 보고 확인하는 검사 이름(실행·계산 검사와 같이 results 에 들어간다) */
    String BLUEPRINT_CHECK = "설계도 형식"; // 설계도 단계
    String FACTS_CHECK = "사실·예가 글에 그대로 들어감"; // 작성 단계(M4: 판단 근거와 결함 근거가 자료에 있다)

    /** 관문 검사(AI) 이름. 답 노출은 Sonnet 과 terra 가 따로 본다 */
    String LEAK_A_GATE = "답 노출 점검(1차)";
    String LEAK_B_GATE = "답 노출 점검(2차)";
    String KEY_GATE = "정답 키 점검"; // BIZ
    String BACKING_GATE = "테스트 근거 점검"; // DEV
    String ALT_GATE = "다른 풀이 실행 점검"; // DEV

    String FILLER_GATE = "관련 적은 자료 점검(M6)"; // 관문(AI, 생성과 다른 회사): 관련 적은 자료에 답·결함 근거·판단 근거가 없다
    String M6_NEEDED_CHECK = "필요한 묶음이 맥락 한도 안(M6)"; // 코드: 필요한 묶음 글자 수 ≤ 맥락 한도의 70%. 실패하면 설계도를 다시
    String M6_TOTAL_CHECK = "자료 전체가 맥락 한도를 넘음(M6)"; // 코드: 자료 전체 ≥ 맥락 한도의 2배. 실패하면 단계 4'를 다시

    String BASIS_CHECK = "사례 근거 문장이 글에 그대로 있음"; // 작성 단계: 사례의 기대 결과를 정하는 문장(M2)

    String BLUEPRINT_SENTENCE_CHECK = "설계도 문장이 관문에 걸림"; // 관문이 지적한 문장이 설계도의 사실·근거·예 문장이거나, 같은 실패가 되풀이됨: 글 단계가 아니라 설계도를 고친다
    String STUB_CHECK = "추가하라는 메서드가 시작 코드에 틀로 있음"; // 시작 코드 단계(DEV): '추가:' 메서드는 설계도 시그니처 그대로 UnsupportedOperationException 틀로 있어야 숨은 테스트가 컴파일된다
    String ADDED_CALLED_CHECK = "추가하라는 메서드를 숨은 테스트가 호출함"; // 정답 쪽 단계(DEV): 새로 만들 메서드를 숨은 테스트가 한 번도 안 부르면 구현 여부를 못 가린다
    String DUPLICATE_CHECK = "같은 문장이 자료에 한 번만 나옴"; // 작성 단계(BIZ)
    String META_CHECK = "자료에 과제 설계자의 말이 없음"; // 작성 단계(BIZ): 문서는 실제 업무 문서여야 한다(응시자·AI·기획안 양식을 말하지 않음)
    String MENTION_CHECK = "설명에서 말한 파일이 실제로 있음"; // 작성 단계: 설명·요구사항이 말한 파일(*.java, *.md, *.pdf, *.csv)이 응시자에게 보이는 파일에 있다
    String FILLER_CHECK = "군더더기 문장 없음"; // 작성 단계(BIZ, 경고): 사람의 동작만 서술하는 문장
    String HAN_CHECK = "한자가 섞이지 않음"; // 글을 만든 단계마다. 상세 앞의 [작성]·[문항]·[정답]이 다시 돌릴 단계를 가른다

    String CHOICE_CHECK = "보기에 다른 문항 정답·판단 지점 객관식 없음"; // 정답 쪽 단계(BIZ)
    String CASES_CHECK = "숨은 테스트·문항이 설계도 사례와 같음"; // 정답 쪽: 사례 밖 동작을 요구하지 않음(M2)
    String SOURCE_PATH_CHECK = "문항 근거 경로가 실제 자료임"; // 정답 쪽(BIZ): 근거 경로가 저장될 파일에 있어야 한다

    Run<D> start(TaskGenRequest form);

    interface Run<D> {

        /** 1단계부터 끝까지 만든다 */
        D create(Consumer<String> onStep);

        /**
         * 실패한 검사들이 어느 종류의 문제인지: "text"(글 단계 WRITING·QUESTIONS 만 고치면 됨), "test"(정답 쪽·시작 코드 등). 다시 만들기 예산을 종류별로 센다.
         * 기본: 답 노출 관문은 text, 나머지는 test
         */
        default java.util.Set<String> kinds(List<GenCheckResultDto> failed) {
            return StageRouteService.kinds(failed, c -> StageRouteService.gateOwners(c, false));
        }

        /** 실패한 검사를 낸 단계부터 다시 만든다. 단계를 정하지 못하면 설계도부터 처음부터 */
        D redo(List<GenCheckResultDto> failed, Consumer<String> onStep);

        /** 지금 Draft 에 대해 코드가 단계 결과로 확인한 검사 결과 */
        List<GenCheckResultDto> checks();

        /** 지금까지 부른 단계 호출의 토큰 기록 */
        List<StageUsageDto> usage();

        /** 지금 설계도(단계별 생성이 아니면 null) */
        default com.pair.domain.taskgen.dto.BlueprintDto blueprint() {
            return null;
        }

        /** failed 로 redo 하면 다시 돌 단계 이름들(비용 어림용). 모르면 빈 목록 */
        default List<String> plannedStages(List<GenCheckResultDto> failed) {
            return List.of();
        }

        /** 마지막 redo 가 다시 돌린 단계 이름들(없으면 빈 목록) */
        List<String> redone();

        /** failed 로 redo 하면 설계도부터 다시 만드는가(설계도 문제이거나 어느 단계 탓인지 모를 때). 단계별 생성이 아니면 false */
        default boolean restartsFrom(List<GenCheckResultDto> failed) {
            return false;
        }
    }
}
