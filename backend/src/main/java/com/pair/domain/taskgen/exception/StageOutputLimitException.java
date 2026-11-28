package com.pair.domain.taskgen.exception;

/**
 * 생성 단계 하나가 추론 강도를 낮춰 다시 불러도 출력 한도에서 빈 답으로 끊겼을 때. 과제 전체를 오류로 끝내지 않고
 * TaskGeneratorService 가 그 단계부터 다시 만든다(단계 다시 만들기 예산 안에서). stage 는 TaskPipelineService.Stage 이름
 */
public class StageOutputLimitException extends RuntimeException {

    private final String stage;

    public StageOutputLimitException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
