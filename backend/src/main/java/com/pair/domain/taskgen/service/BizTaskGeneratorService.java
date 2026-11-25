package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 비개발(BIZ) 과제 생성기. 프롬프트·출력 형식·검사 목록·저장만 정한다 (PoC poc/taskgen/taskgen_biz.py 를 옮김) */
@Component
public class BizTaskGeneratorService extends TaskGeneratorService<BizDraftDto> {

    /*
     * 프롬프트 규칙과 기준 문서(pAIr-docs 설계/좋은_과제_기준.md) 번호 대응. 텍스트 블록 안에는 주석을 쓸 수 없어 여기에 모아 둔다.
     *  - PURPOSE(맨 앞) ..................... 1장 핵심 정의, M3, M5, Q3
     *  - BOUNDARY ........................... 2장 명세와 답 노출의 경계 (M1, M4)
     *  - 가상의 회사·수치, 현실감 ............... Q2
     *  - 자료 두 종류(csvFiles, documents) ..... 형식, 5장 표(자료 분량은 깊이 규칙 우선)
     *  - 근거 문서 2개 이상, 계산 재현 .......... M1, 4장 BIZ
     *  - 함정 근거는 사실 진술·문단에 섞음 ....... M4, Q3, 4장 BIZ (검사 EVIDENCE_STYLE)
     *  - 용어 정의는 뜻만, 계산 절차 금지 ........ 4장 BIZ, 2장 경계, Q5
     *  - 함정이 결론을 바꿈(correct/wrongConclusion) .. M3 (검사 CONCLUSION)
     *  - 검토 중인 안은 정답이 아님 .............. 4장 BIZ
     *  - checklist ......................... (채점 기준. 기준 문서 대상 아님)
     *  - questions 구성·함정 문항·결론 객관식 ..... M3, M5 (검사 AI 답 그대로 내면 함정에 걸림이 이 문항들을 본다)
     *  - sources 2개 이상 ................... M1, Q4
     *  - 단답형 단위·반올림 ................... M1, M2(인정 답안)
     *  - followUps ......................... M4
     *  - trapKeywords ...................... M4
     *  - commonWrongAnswers ................. M3, M5
     *  - 과제 깊이(depth) ................... Q1, 5장 표
     *  - 품질 규칙(Q4, Q5) .................. Q4, Q5
     */
    private static final String SYSTEM = PURPOSE + BOUNDARY + """
            당신은 AI 활용 역량 평가 플랫폼의 비개발 과제 출제자입니다. 응시자는 %d분 동안 AI 어시스턴트와 함께 자료를 분석해 1페이지 기획안을 쓰고, 자료에 근거한 문항에 답합니다.
            규칙:
            - 실제 업무에서 볼 법한 상황, 자료, 말투로 쓰고, 실제 회사처럼 보이는 가상의 회사와 수치를 씁니다. 실존 회사 이름은 쓰지 않습니다. 일부러 만든 티가 나는 오답이나 어색한 설정, 읽기 어렵게 꼬인 문장은 쓰지 않습니다.
            - 자료는 두 종류입니다(제한 시간별 개수는 아래 깊이 규칙을 따름). csvFiles: 자료/이름.csv 1~2개(쉼표 구분, 첫 줄 머리글, 칸 안에 쉼표 없음). documents: 자료/이름.pdf 2~3개이며 각 문서를 쪽별 글(pages 배열)로 씁니다. 서버가 쪽 글을 그대로 PDF 로 만듭니다. 쪽 하나는 900자 이내, 30줄 이내로 쓰고 마크다운 기호(#, **, 표)는 쓰지 않습니다. 이모지와 특수 기호도 쓰지 않습니다. 자료 전체는 합쳐서 3,000~10,000자(20분은 깊이 규칙의 분량).
            - 근거 문서는 2개 이상이고, 모든 정답 계산을 자료의 숫자로 다시 할 수 있어야 합니다. 자료에 없는 값이나 서로 모순되는 내용은 없어야 합니다.
            - 함정은 자료를 꼼꼼히 보면 피할 수 있어야 합니다. 함정을 알아챌 근거 문장을 자료(문서 쪽 글이나 CSV)에 넣고, trap.evidence 에 그 문장을 한 줄로 그대로 복사합니다. 속임수나 자료 밖 지식이 필요한 함정은 쓰지 않습니다. 과제 설명에는 함정을 알려 주지 않습니다.
            - trap.evidence 는 사실 진술(측정 범위, 집계 정의, 같은 기간에 있었던 일)로 씁니다. "더해서는 안 된다", "혼동하지 않는다" 같은 해석 지시는 쓰지 않습니다. 근거 문장은 맥락 없이 따로 떠 있는 한 줄 문단으로 두지 말고, 다른 내용과 같은 문단 안에 자연스럽게 섞습니다.
            - 자료의 용어와 지표 정의는 그 말이 무엇을 뜻하는지만 씁니다. 어떻게 구하는지(계산 절차, 단계별 계산식, "각각 곱해 더한다" 같은 안내)는 쓰지 않습니다. 업계 지식이 없어도 풀 수 있게 필요한 용어와 규칙은 자료 안에 씁니다.
            - 함정에 빠진 계산으로 내린 결론(추천안, 통과 여부)이 정답 결론과 달라지도록 수치를 정합니다. 함정에 빠져도 같은 결론이 나오면 함정이 판단을 가르지 못합니다. trap.correctConclusion 에 정답 결론을, trap.wrongConclusion 에 함정에 빠졌을 때의 결론을 한 문장으로 씁니다. 둘은 서로 달라야 합니다.
            - 자료에 제시하는 '검토 중인 안'(검토 집행액, 운영팀이 요청한 안 등)은 정답이 아닌 안으로 둡니다. 대안이 여러 개면 그중 1개 이상은 함정에 빠진 사람이 고를 안으로 둡니다. 정답 조합이 자료에 그대로 적혀 있으면 안 됩니다.
            - checklist 는 숨은 채점 기준입니다. '## 필수 항목' 아래 번호 목록(결론, 자료 수치를 인용한 근거 3개, 함정 관련 계산, 리스크와 대응), '## 참고 계산'(정답 계산 과정), '## 흔한 함정'을 마크다운으로 씁니다.
            - questions 의 개수와 유형 구성은 아래 깊이 규칙을 따릅니다. 객관식(SINGLE)은 보기 4개에 정답 1개. 단답형(SHORT)은 자료의 수치로 계산하는 숫자 하나가 답이고 calc 에 계산식을 씁니다. 함정에 빠지면 답이 달라지는 단답형을 1개 이상 넣고, 결론(추천안, 통과 여부)을 묻는 객관식을 1개 이상 넣습니다. 결론 객관식의 정답 보기는 trap.correctConclusion 과, 함정에 빠진 사람이 고를 보기는 trap.wrongConclusion 과 같은 문구로 씁니다.
            - 문항마다 sources 에 근거 자료를 2개 이상 적습니다. 서로 다른 문서(또는 CSV)의 경로와 쪽 번호(예: path "자료/설문 요약.pdf", pages "1,2")입니다. 정답은 문서 여러 개를 함께 봐야 알 수 있게 만들고, 한 문서만으로 풀리지 않게 합니다. AI 답을 이 자료의 칸이나 문장과 하나씩 대조해 보면 틀린 곳이 드러나야 합니다. 경로와 쪽 번호는 실제로 있어야 합니다(CSV 는 pages 를 빈 문자열로).
            - calc 형식: v("자료/파일.csv", "열=값;열=값", "열 이름") 이 그 조건에 맞는 한 행의 칸 값입니다(조건에 맞는 행은 정확히 1개). 사칙연산, 괄호, round(x, 자릿수) 만 씁니다. 예: round(v("자료/매출.csv", "월=2026-10;요금제=베이직", "가입자 수") * 0.34 * (9900 - 5900) / 10000)
            - 단답형 문항에는 답의 단위와 반올림 방법(예: 만 원 단위, 소수 첫째 자리 반올림)을 적습니다. accepted 첫 번째는 그 단위의 숫자만 씁니다(쉼표 없이).
            - followUps 는 후속 질문 3개입니다. 앞의 2개는 공용 질문(직접 검증한 것은 무엇이고 어떻게 확인했나, 채택하지 않은 AI 제안과 이유)이고, 3번째는 이 과제의 함정에 맞춘 질문 1개입니다(예: "AI 가 계산한 전환 인원을 어느 자료 어느 칸의 값과 맞춰 봤나요?"). 3번째 질문에는 trapKeywords 를 쓰지 않고 검증 방법만 묻습니다.
            - trapKeywords 는 함정의 핵심어 2~5개입니다(응시자가 알면 정답이 거의 드러나는 단어, 예: 의향, 보정). description 과 requirements, 자료 파일 이름에 이 단어를 쓰지 말고 현상만 설명합니다. 함정 근거 문장은 자료 본문에만 둡니다.
            - 단답형(SHORT) 문항마다 commonWrongAnswers 에 "AI 가 자료를 그대로 받아 한 번에 답할 때 낼 법한 틀린 값(함정에 빠진 값)" 1~2개를 씁니다. 각각 value(숫자만, 쉼표 없이)와 reason(왜 그 값이 나오는지)입니다. 틀린 값은 인정 답안과 0.5%% 넘게 달라야 합니다. 객관식(SINGLE)은 빈 배열로 둡니다.
              예: {"value": "62000", "reason": "전환율 보정 없이 가입자 전체를 곱함"}
            - code 는 과제를 나타내는 영문 소문자와 하이픈만 쓴 짧은 이름(예: lite-plan), summary 는 과제 목록 카드에 보일 한국어 한 문장(80자 이내, 함정을 드러내지 않음), category 는 과제 형태를 나타내는 짧은 한국어(예: 기획안 작성, 데이터 분석).
            - 과제 깊이는 제한 시간에 맞춥니다: %s
            - 모든 글은 한국어로 씁니다.""";

    /** 제한 시간별 과제 깊이. 기준 문서 Q1, 5장 표와 같은 값(2026-11-24 확인) */
    static String depth(int minutes) {
        return switch (minutes) {
            case 20 -> "20분 = CSV 1개 + 문서 2개(각 1~2쪽), 문항 3개(객관식 2개, 단답형 1개). 자료는 합쳐서 1,500~4,000자로 짧게 만듭니다.";
            case 40 -> "40분 = CSV 1~2개 + 문서 2개, 문항 4개(객관식 2개, 단답형 2개).";
            default -> "60분 = CSV 1~2개 + 문서 2~3개, 문항 4~6개(객관식 2~3개, 단답형 2~3개).";
        };
    }

    private final PdfService pdf;
    private final TaskGenSolverService solver;
    private final TaskGenProperties props;
    private final GeneratedTaskRepository writer;
    private final BizSoloSolveService solo;

    public BizTaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, PdfService pdf, TaskGenSolverService solver,
            GeneratedTaskRepository writer, BizSoloSolveService solo) {
        super(ai, props);
        this.props = props;
        this.pdf = pdf;
        this.solver = solver;
        this.writer = writer;
        this.solo = solo;
    }

    /**
     * M5(BIZ): 다른 fail 검사가 모두 통과한 Draft 에만 "AI 답을 그대로 내면 함정에 걸림" 검사를 돌린다(비용 절약).
     * 그대로 내도 통과하면 용도와 상관없이 fail(다시 생성하며 AI 답과 단서를 알려 줌). AI 호출이 실패하면 warn.
     */
    @Override
    protected Extra afterChecks(TaskGenRequest form, BizDraftDto draft, List<GenCheckResultDto> results) {
        if (solo == null || !solo.enabled()) return skip("pair.taskgen.solo-enabled=false");
        if (results.stream().anyMatch(GenCheckResultDto::needsRegeneration)) return skip("다른 검사가 실패해 돌리지 않음");
        try {
            BizSoloSolveService.Result r = solo.run(draft);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("soloTokens", Map.of("input", r.inputTokens(), "output", r.outputTokens()));
            d.put("soloAnswers", r.answers());
            return new Extra(List.of(r.check()), d, Map.of());
        } catch (RuntimeException e) {
            return new Extra(List.of(new GenCheckResultDto(BizSoloSolveService.NAME, true, "AI 단독 풀이를 확인하지 못했어요(호출 실패)",
                    GenCheckResultDto.WARN)), Map.of(), Map.of());
        }
    }

    private static Extra skip(String why) {
        return new Extra(List.of(BizSoloSolveService.skipped(why)), Map.of(), Map.of());
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
    protected Class<BizDraftDto> draftType() {
        return BizDraftDto.class;
    }

    @Override
    protected List<GenCheckService<BizDraftDto>> checks(TaskGenRequest form, BizDraftDto draft) {
        return BizCheckService.forDraft(pdf, solver, props.solverModel(), draft, form.minutes(), form);
    }

    @Override
    protected Map<String, Object> details(BizDraftDto draft) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("solverModel", props.solverModel());
        if (draft.trap() != null) m.put("trap", draft.trap());
        m.put("trapKeywords", draft.trapKeywords());
        // 문항 검수용: 계산식과 흔한 오답은 DB 에 저장되지 않으므로 보고서에 남긴다
        if (draft.questions() != null) {
            List<Map<String, Object>> qs = new java.util.ArrayList<>();
            int seq = 1;
            for (var q : draft.questions()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("seq", seq++);
                e.put("type", q.type());
                e.put("calc", q.calc());
                e.put("commonWrongAnswers", q.commonWrongAnswers());
                qs.add(e);
            }
            m.put("questionNotes", qs);
        }
        return m;
    }

    @Override
    protected List<String> draftCompetencies(BizDraftDto draft) {
        return draft.competencies();
    }

    @Override
    protected String save(TaskGenRequest form, BizDraftDto draft, GenReport report, long adminId) {
        return writer.saveBiz(form, draft, report, adminId);
    }
}
