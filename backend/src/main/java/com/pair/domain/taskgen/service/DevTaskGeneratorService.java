package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pair.infra.sandbox.CodeRunner;

/** 개발(DEV) 과제 생성기. 프롬프트·출력 형식·검사 목록만 정한다 (PoC poc/taskgen/taskgen.py 를 옮김) */
@Component
public class DevTaskGeneratorService extends TaskGeneratorService<DevDraftDto> {

    private static final String SYSTEM = """
            당신은 AI 활용 역량 평가 플랫폼의 개발 과제 출제자입니다. 응시자는 %d분 동안 AI 어시스턴트와 함께 버그를 찾아 고칩니다.
            규칙:
            - 순수 Java 21 + JUnit 5 만 씁니다. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않습니다.
            - 패키지는 하나(예: shop)이고 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java 입니다.
            - 시작 코드에는 함정으로 주어진 버그가 실제로 있어야 하며, 코드만 봐서는 바로 드러나지 않게 자연스럽게 섞습니다. 버그 위치를 주석으로 알려 주지 않습니다.
            - 공개 테스트는 시작 코드에서 통과하는 기본 동작만 확인합니다.
            - 숨은 테스트는 시작 코드에서 반드시 실패하고, 올바르게 고치면 반드시 통과해야 합니다. 동시성 테스트는 assertTimeoutPreemptively 로 10초 안에 끝나게 하고, 결과가 흔들리지 않게 충분히 반복합니다.
            - 정답(solution)은 시작 코드의 src 파일 중 바뀐 파일의 전체 내용입니다. 테스트 파일은 바꾸지 않습니다.
            - 설명과 요구사항은 한국어로, 실제 회사의 장애 보고처럼 씁니다.
            - 과제 깊이는 제한 시간에 맞춥니다: %s
            - difficulty 는 만든 과제의 수준을 보고 초급, 중급, 고급 중 하나로 정합니다.
            - code 는 과제를 나타내는 영문 소문자와 하이픈만 쓴 짧은 이름입니다(예: coupon-discount).
            - summary 는 과제 목록 카드에 보일 한국어 한 문장(80자 이내)입니다. 정답이나 버그 위치를 드러내지 않습니다.
            - category 는 과제 형태를 나타내는 한국어 짧은 말입니다(예: 장애 분석, 기능 구현, 성능 개선).
            - trapKeywords 는 함정의 핵심어 2~5개입니다. 응시자가 알면 정답이 거의 드러나는 단어(예: 서머타임, 전환일, 24시간)입니다. description, requirements, 응시자에게 보이는 파일 이름과 주석(시작 코드, 공개 테스트, NOTES.md, logs 안의 글 포함)에 이 단어를 쓰지 말고, 나타나는 현상만 설명합니다.
            - wrongFixes 는 "AI 가 그럴듯하게 제안할 법하지만 틀린 수정" 2~3개입니다. 각각 reason(왜 틀렸는지 한 줄)과 files(바뀐 src 파일의 전체 내용)를 씁니다. 시작 코드에 덮어쓰면 컴파일되고 공개 테스트는 통과하지만, 숨은 테스트는 1개 이상 실패해야 합니다. 정답과 같은 동작을 하는 다른 풀이는 오답이 아닙니다. 숨은 테스트는 이 오답들을 모두 떨어뜨리도록 씁니다.
              예: {"reason": "하루를 24시간으로 더해 날짜가 없는 날에서 틀린다", "files": [{"path": "src/shop/DailyWindow.java", "content": "package shop; ..."}]}
            - 장애 분석 과제(category 에 '장애')는 재현 로그나 접수 메모를 응시자 파일로 files 에 넣습니다: logs/이름.log 또는 NOTES.md(INCIDENT.md), 공백 뺀 200자 이상. 언제 어떤 입력에서 어떤 값이 나왔는지 현상만 적고 원인 단어는 쓰지 않습니다. 이 파일들은 경로 규칙(src/·test/)의 예외입니다.
            - 정답의 변경량이 제한 시간에 맞아야 합니다. 정답과 시작 코드의 바뀐 줄(추가+삭제 합)이 20분은 2줄 이상, 40분은 6줄 이상, 60분은 12줄 이상이 되도록 버그를 설계합니다. 한 줄만 고치면 되는 과제는 만들지 않습니다.
            - taskFollowUp 은 이 과제의 함정에 맞춘 후속 질문 1개입니다(예: "AI 가 낸 수정이 다른 입력에서도 맞는지 어떻게 확인했나요?"). trapKeywords 와 숨은 테스트 메서드 이름을 쓰지 않고, 검증 방법만 묻습니다. 앞의 공용 질문 2개는 서버가 붙입니다.""";

    /** 제한 시간별 과제 깊이 */
    static String depth(int minutes) {
        return switch (minutes) {
            case 20 -> "20분 = 파일 2~3개, 버그 1개, 숨은 테스트 1~2개. 짧은 시간에 읽고 고칠 수 있게 작게 만듭니다.";
            case 40 -> "40분 = 파일 3~5개. 버그 1~2개 규모로 만듭니다.";
            default -> "60분 = 파일 4~6개. 여러 파일을 따라가야 원인이 드러나는 규모로 만듭니다.";
        };
    }

    private final CodeRunner runner;
    private final GeneratedTaskRepository writer;

    public DevTaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, CodeRunner runner, GeneratedTaskRepository writer) {
        super(ai, props);
        this.runner = runner;
        this.writer = writer;
    }

    @Override
    protected String system(TaskGenRequest form) {
        return SYSTEM.formatted(form.minutes(), depth(form.minutes())) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    @Override
    protected String user(TaskGenRequest form, List<GenCheckResultDto> failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("제한 시간: ").append(form.minutes()).append("분\n")
                .append("주제: ").append(form.topic()).append('\n')
                .append("함정: ").append(form.trap()).append('\n')
                .append("위 조건으로 과제를 만들어 주세요.");
        if (!failed.isEmpty()) {
            sb.append("\n\n앞서 만든 과제는 자동 확인에서 아래 검사에 실패했습니다. 이 문제를 고쳐 과제를 처음부터 다시 만들어 주세요.");
            for (GenCheckResultDto c : failed) sb.append("\n- ").append(c.name()).append(": ").append(c.detail());
        }
        return sb.toString();
    }

    @Override
    protected Class<DevDraftDto> draftType() {
        return DevDraftDto.class;
    }

    @Override
    protected List<GenCheckService<DevDraftDto>> checks(TaskGenRequest form, DevDraftDto draft) {
        return DevCheckService.forDraft(runner, draft, form);
    }

    @Override
    protected Map<String, Object> details(DevDraftDto draft) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trapKeywords", draft.trapKeywords());
        m.put("taskFollowUp", draft.taskFollowUp());
        if (draft.wrongFixes() != null) m.put("wrongFixReasons", draft.wrongFixes().stream().map(DevDraftDto.WrongFix::reason).toList());
        return m;
    }

    @Override
    protected List<String> draftCompetencies(DevDraftDto draft) {
        return draft.competencies();
    }

    @Override
    protected String save(TaskGenRequest form, DevDraftDto draft, GenReport report, long adminId) {
        return writer.saveDev(form, draft, report, adminId);
    }
}
