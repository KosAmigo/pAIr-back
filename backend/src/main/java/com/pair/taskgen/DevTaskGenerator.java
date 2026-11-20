package com.pair.taskgen;

import java.util.List;

import org.springframework.stereotype.Component;

import com.pair.run.CodeRunner;

/** 개발(DEV) 과제 생성기. 프롬프트·출력 형식·검사 목록만 정한다 (PoC poc/taskgen/taskgen.py 를 옮김) */
@Component
public class DevTaskGenerator extends TaskGenerator<DevDraft> {

    private static final String SYSTEM = """
            당신은 AI 활용 역량 평가 플랫폼의 개발 과제 출제자입니다. 응시자는 %d분 동안 AI 어시스턴트와 함께 버그를 찾아 고칩니다.
            규칙:
            - 순수 Java 21 + JUnit 5 만 씁니다. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않습니다.
            - 패키지는 하나(예: shop)이고 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java 입니다.
            - 시작 코드에는 함정으로 주어진 버그가 실제로 있어야 하며, 코드만 봐서는 바로 드러나지 않게 자연스럽게 섞습니다. 버그 위치를 주석으로 알려 주지 않습니다.
            - 공개 테스트는 시작 코드에서 통과하는 기본 동작만 확인합니다.
            - 숨은 테스트는 시작 코드에서 반드시 실패하고, 올바르게 고치면 반드시 통과해야 합니다. 동시성 테스트는 assertTimeoutPreemptively 로 10초 안에 끝나게 하고, 결과가 흔들리지 않게 충분히 반복합니다.
            - 정답(solution)은 시작 코드의 src 파일 중 바뀐 파일의 전체 내용입니다. 테스트 파일은 바꾸지 않습니다.
            - 설명과 요구사항은 한국어로, 실제 회사의 장애 보고처럼 씁니다. 파일 3~6개 규모로 만듭니다.
            - code 는 과제를 나타내는 영문 소문자와 하이픈만 쓴 짧은 이름입니다(예: coupon-discount).
            - summary 는 과제 목록 카드에 보일 한국어 한 문장(80자 이내)입니다. 정답이나 버그 위치를 드러내지 않습니다.
            - category 는 과제 형태를 나타내는 한국어 짧은 말입니다(예: 장애 분석, 기능 구현, 성능 개선).""";

    private final CodeRunner runner;
    private final GeneratedTaskWriter writer;

    public DevTaskGenerator(TaskGenAi ai, TaskGenProperties props, CodeRunner runner, GeneratedTaskWriter writer) {
        super(ai, props);
        this.runner = runner;
        this.writer = writer;
    }

    @Override
    protected String system(TaskGenForm form) {
        return SYSTEM.formatted(form.minutes());
    }

    @Override
    protected String user(TaskGenForm form, List<CheckResult> failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("직군: ").append(form.jobRole()).append('\n')
                .append("난이도: ").append(form.difficulty()).append('\n')
                .append("주제: ").append(form.topic()).append('\n')
                .append("함정: ").append(form.trap()).append('\n')
                .append("위 조건으로 과제를 만들어 주세요.");
        if (!failed.isEmpty()) {
            sb.append("\n\n앞서 만든 과제는 자동 확인에서 아래 검사에 실패했습니다. 이 문제를 고쳐 과제를 처음부터 다시 만들어 주세요.");
            for (CheckResult c : failed) sb.append("\n- ").append(c.name()).append(": ").append(c.detail());
        }
        return sb.toString();
    }

    @Override
    protected Class<DevDraft> draftType() {
        return DevDraft.class;
    }

    @Override
    protected List<GenCheck<DevDraft>> checks(DevDraft draft) {
        return DevChecks.forDraft(runner, draft);
    }

    @Override
    protected String save(TaskGenForm form, DevDraft draft, GenReport report, long adminId) {
        return writer.saveDev(form, draft, report, adminId);
    }
}
