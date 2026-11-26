package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 답 노출 관문 프롬프트(leak.md) 확인용 실제 모델 호출(2026-11-26 g8 결정: 회사 정의 기록은 허용). PAIR_LEAK_LIVE=1 일 때만 돈다(비용 발생).
 * 1차(REVIEW)·2차(REVIEW2) 모델에 짧은 입력을 하나씩 보내고 build/leak-live.txt 에 결과를 적는다. 판정은 사람이 본다(assert 없음).
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_LEAK_LIVE", matches = "1")
@Tag("slow")
class LeakPromptLiveTest {

    /** 배치 라이브 테스트와 같은 키 설정(examtest 프로필은 빈 키를 쓴다) */
    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean org.springframework.mail.javamail.JavaMailSender mail;
    @Autowired TaskGenAiService ai;
    @Autowired TaskGenProperties props;

    record Sample(String id, boolean shouldFlag, String flaw, String doc, String sentence) {}

    private static final String BG = "담당자는 이번 달 검토 회의 일정을 팀 달력에 올렸다. 회의에는 운영팀과 재무팀이 함께 참석했다. ";

    static final List<Sample> SAMPLES = List.of(
            // g8-biz 에서 걸린 회사 정의 기록(이제 허용돼야 함)
            new Sample("g8-01", false, "B9 월 총매출 증가를 일평균 매출 증가로 해석하지만 보고 대상은 달력의 정규 판매 영업이다", "자료/매출 검토 기록.pdf",
                    "이번 성장 보고서 표지의 집계 대상란에는 '달력에 정규 판매로 표시된 영업'이 적혀 있다."),
            new Sample("g8-02", false, "B9 매체 집행비만 지출로 보고 대행 수수료를 빼면 총지출 대비 잔여이익률이 달라진다", "자료/대행사 정산 기록.pdf",
                    "대행사 담당자는 이번 청구서의 대행 수수료가 광고 성과 파일의 매체 집행비에 들어 있지 않다고 정산 확인란에 답변했다."),
            new Sample("g8-03", false, "B10 재제출을 최신 응답으로 교체하거나 별도 응답자로 넣으면 최초 완료본 채택 기록과 달라진다 / B2 참여자 비중으로 평균하면 재직자 구성 반영 기준과 달라진다",
                    "자료/인사위원회 검토 자료.pdf", "지난 제출본의 응답 채택란에는 '직원별 최초 완료본'이라고 적혀 있다. 지난 인사위원회 제출본의 집계 기준란에는 '부서별 재직자 구성 반영'이라고 적혀 있다."),
            new Sample("g8-04", false, "B9 청구 완료된 구매 잔량을 모두 원료 확보량으로 읽어 검수 완료 기록이 없는 물량까지 포함한다", "자료/제품 규격서.pdf",
                    "지난달 규격서 부속 검토표의 작성 이력에는 집계 기준이 검수 완료로 기재되어 있다."),
            new Sample("g8-08", false, "B10 참석 인원은 방문 확인된 신청 번호 기준인데 현장 기록 행 수를 세면 재입장이 별도 참석자로 잡힌다", "자료/행사 운영 검토서.pdf",
                    "지난 행사 결과표의 참석 인원 항목에는 현장 방문이 확인된 신청 번호의 개수가 기재되었다."),
            // 여전히 잡아야 하는 것(계산 절차, 응시자에게 칸을 보라는 요청, 지시, 정답 말하기)
            new Sample("m3-01", true, "항목마다 원 단위로 반올림한 뒤 더해 합계 전체를 한 번만 반올림한 값과 달라진다", "자료/회계팀 메모.pdf",
                    "회계팀 메모: 항목별 할인 금액은 원 단위로 반올림해 장부에 올리지 않고, 할인 적용 합계 전체를 마지막에 한 번 원 단위로 반올림해 올린다고 적혀 있다."),
            new Sample("g4-06", true, "부가세가 포함된 기본운임에 유류 공급가액만 더해 서로 다른 세금 기준의 금액을 합산한다", "자료/구매 검토 기록.pdf",
                    "가온운송 원본 견적서의 기본운임 합계란에는 부가세 포함 금액이라고 적혀 있다. 해당 견적을 인용할 때는 금액과 합계란의 문구가 함께 드러나는 설명을 바란다."),
            new Sample("syn-proc", true, "B5 누계 표를 월별 값으로 읽으면 6월 접수량이 부풀려진다", "자료/운영대장.pdf",
                    "운영대장의 월 값은 누계에서 전월 누계를 빼서 구해야 하며, 누계를 그대로 월 값으로 쓰면 안 된다."),
            new Sample("syn-answer", true, "B9 판매액으로 증가율을 보면 두 채널 모두 접수 대상이 되지만 수수료를 뺀 입금액으로 보면 온라인 채널만 대상이다", "자료/채널 운영 보고.pdf",
                    "검토 결과 수수료를 뺀 입금액 기준으로 보면 이번 분기 접수 대상은 온라인 채널 한 곳뿐이다."),
            new Sample("g7-d03", true, "R5 청구 용량 계산에서 같은 내용 식별자를 가진 여러 등록 건을 각각 청구한다", "NOTES.md",
                    "청구 용량을 검토할 때는 등록 화면의 내용 식별자 칸이 같은 건을 한 번만 세는지 확인해 주세요."));

    static String input(Sample s) {
        return "직군: BIZ, 제한 시간: 20분\n[숨은 결함(출제자만 아는 정보)]\n- " + s.flaw() + "\n[설명]\n자료를 검토해 1페이지 기획안을 쓰고 문항에 답하세요.\n[요구사항]\n결론과 근거 3개를 쓴다.\n"
                + "\n[문서 " + s.doc() + "]\n(1쪽) " + BG + s.sentence() + " 다음 회의는 다음 달 첫째 주에 열린다.\n";
    }

    @Test
    void run() throws Exception {
        StageCallService calls = new StageCallService(ai, props, false);
        TaskReviewService review = new TaskReviewService(calls);
        StringBuilder out = new StringBuilder();
        for (Sample s : SAMPLES) {
            for (Stage st : List.of(Stage.REVIEW, Stage.REVIEW2)) {
                String line;
                try {
                    var f = review.run(TaskReviewService.Check.LEAK, st, input(s));
                    boolean flagged = !f.violations().isEmpty();
                    line = s.id() + " | " + st + " | 기대 " + (s.shouldFlag() ? "잡음" : "통과") + " | 결과 " + (flagged ? "잡음" : "통과") + (flagged == s.shouldFlag() ? " | OK" : " | 어긋남")
                            + (flagged ? " | " + f.violations().get(0).quote() : "");
                } catch (RuntimeException e) {
                    line = s.id() + " | " + st + " | 호출 실패 " + e.getMessage();
                }
                out.append(line).append('\n');
            }
        }
        out.append("calls ").append(calls.usage().size()).append(", cost $").append(String.format("%.3f", StageCallService.cost(props, calls.usage()))).append('\n');
        Files.writeString(Path.of("build", "leak-live.txt"), out.toString(), StandardCharsets.UTF_8);
    }
}
