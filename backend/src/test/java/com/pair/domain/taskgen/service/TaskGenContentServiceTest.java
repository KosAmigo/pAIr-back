package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizBlueprintDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevBlueprintDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.FillerDto;
import com.pair.domain.taskgen.dto.TaskGenContentResponse;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.entity.TaskGenJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 저장된 모델 호출(가짜 json)로 중간 결과 요약을 확인한다. DB·AI 없음 */
class TaskGenContentServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");
    private final TaskGenContentService service = new TaskGenContentService(null, null, JSON);

    private int seq;

    private TaskGenCall call(String stage, String label, Object result, int minute) {
        return call(stage, label, text(result), minute);
    }

    private TaskGenCall call(String stage, String label, String result, int minute) {
        return new TaskGenCall("c" + (seq++), "j1", "h" + seq, 0, stage, label, "anthropic", result, 1, 1, T0.plusSeconds(minute * 60L));
    }

    private static String text(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TaskGenJob job(String track) {
        String form = "{\"track\":\"" + track + "\",\"topic\":\"쿠폰\",\"trap\":\"중복 적용\",\"usage\":\"SELF\",\"minutes\":40,\"competencies\":[]}";
        return new TaskGenJob("j1", null, "env", "BATCH", null, form, 1, T0);
    }

    private static DevBlueprintDto devBlueprint(String title) {
        return new DevBlueprintDto(title, "요약", "C1", "동시성", List.of("control"), "상황", List.of("int apply()"), List.of(),
                List.of(new BlueprintDto.Example("1", "2")), List.of(new BlueprintDto.Flaw("결함", "Foo.java", "근거", "t1")),
                List.of(new BlueprintDto.Judgment("어느 쪽?", "근거", List.of(new BlueprintDto.Option("A", "f"), new BlueprintDto.Option("B", "g")))),
                List.of(), List.of(), List.of("src/Foo.java"), List.of(new BlueprintDto.Filler("src/Bar.java", "보조", true)));
    }

    private static DevDraftDto.FileDraft file(String path, String content) {
        return new DevDraftDto.FileDraft(path, content);
    }

    @Test
    @DisplayName("DEV: 단계마다 요약하고, 코드·문서 본문 대신 경로와 글자 수만, 숨은 테스트는 @Test 메서드 이름을 뽑는다")
    void dev() {
        String test = "class T {\n  @Test\n  void first() {}\n  @Test @DisplayName(\"x\")\n  public void second() {}\n  @Test(timeout = 5)\n  static void third() {}\n  void helper() {}\n}";
        List<TaskGenCall> calls = List.of(
                call("BLUEPRINT", null, devBlueprint("쿠폰 계산"), 1),
                call("WRITING", null, new DevWritingDto(List.of("설명"), List.of("요구"), "n".repeat(2500), "후속"), 2),
                call("CODE", null, new DevCodeDto(List.of(file("src/Foo.java", "12345"))), 3),
                call("ANSWER", null, new DevAnswerDto("답", List.of(file("test/HiddenTest.java", test)), List.of(),
                        List.of(new DevDraftDto.AltSolution("다른", List.of())), List.of(new DevDraftDto.WrongFix("틀림", List.of()), new DevDraftDto.WrongFix("틀림2", List.of()))), 4),
                call("FILLER", null, new FillerDto(List.of(file("src/Bar.java", "abc"))), 5),
                call("REVIEW", "LEAK", "{\"not\":\"used\"}", 6));

        TaskGenContentResponse r = service.summarize(job("DEV"), calls);

        assertThat(r.form().track()).isEqualTo("DEV");
        assertThat(r.form().mode()).isEqualTo("BATCH");
        assertThat(r.blueprint().title()).isEqualTo("쿠폰 계산");
        assertThat(r.blueprint().flaws()).hasSize(1);
        assertThat(r.blueprint().judgments().get(0).options()).containsExactly("A", "B");
        assertThat(r.blueprint().fillers().get(0).confusing()).isTrue();
        assertThat(r.writing().notes()).hasSize(NOTES + 1).endsWith("…");
        assertThat(r.code().files().get(0).chars()).isEqualTo(5);
        assertThat(r.answer().hiddenTests().get(0).methods()).containsExactly("first", "second", "third");
        assertThat(r.answer().wrongFixes()).isEqualTo(2);
        assertThat(r.answer().altSolutions()).isEqualTo(1);
        assertThat(r.fillers().files().get(0).path()).isEqualTo("src/Bar.java");
        assertThat(r.questions()).isNull();
        assertThat(r.updatedAt()).isEqualTo(T0.plusSeconds(6 * 60L));
    }

    private static final int NOTES = TaskGenContentService.NOTES_MAX;

    @Test
    @DisplayName("BIZ: 설계도(문서·문항 계획), 요청 글+문서별 글자 수, 문항 종류, 정답 키, 자료를 요약하고 DEV 칸(code)은 null")
    void biz() {
        BizBlueprintDto bp = new BizBlueprintDto("매출 분석", "요약", "B1", "분석", List.of("usage"), "상황", List.of(), List.of(new BizBlueprintDto.Flaw("누락", "표", "근거", "Q1")),
                List.of(), List.of(), null, List.of(), List.of(), List.of(new BlueprintDto.Doc("자료/a.pdf", "보고서")), List.of("Q1 객관식", "Q2 단답형"),
                List.of(), List.of("자료/a.pdf"), List.of());
        List<TaskGenCall> calls = List.of(
                call("BLUEPRINT", null, bp, 1),
                call("WRITING", null, new BizWritingDto(List.of(), List.of(), List.of(new BizDraftDto.DocDraft("자료/a.pdf", List.of("1234", "56")))), 2),
                call("WRITING", null, new BizWritingDto(List.of("요청"), List.of("요구"), List.of(), "후속"), 3),
                call("QUESTIONS", null, new BizQuestionsDto(List.of(new BizQuestionsDto.Question("Q1", "결론은?", List.of("가", "나")),
                        new BizQuestionsDto.Question("Q2", "얼마?", List.of()))), 4),
                call("ANSWER", null, new BizAnswerDto("채점", List.of(new BizAnswerDto.Key("Q1", List.of(), "", List.of(), List.of()),
                        new BizAnswerDto.Key("Q2", List.of("10"), "", List.of(), List.of()))), 5),
                call("FILLER", null, new FillerDto(List.of(file("자료/b.csv", "a,b"))), 6));

        TaskGenContentResponse r = service.summarize(job("BIZ"), calls);

        assertThat(r.blueprint().docs()).hasSize(1);
        assertThat(r.blueprint().questionPlan()).hasSize(2);
        assertThat(r.writing().description()).containsExactly("요청");
        assertThat(r.writing().documents()).hasSize(1);
        assertThat(r.writing().documents().get(0).chars()).isEqualTo(6);
        assertThat(r.questions()).extracting(q -> q.type()).containsExactly("객관식", "단답형");
        assertThat(r.questions().get(1).seq()).isEqualTo(2);
        assertThat(r.answer().keys()).containsExactly("Q1", "Q2");
        assertThat(r.code()).isNull();
        assertThat(r.fillers().files()).hasSize(1);
    }

    @Test
    @DisplayName("다시 만들기 뒤에는 단계마다 가장 최근 결과만 보이고, 아직 없는 단계는 null")
    void latestWins() {
        List<TaskGenCall> calls = new ArrayList<>(List.of(
                call("BLUEPRINT", null, devBlueprint("옛 제목"), 1),
                call("BLUEPRINT", null, devBlueprint("새 제목"), 5),
                call("CODE", null, new DevCodeDto(List.of(file("src/Old.java", "x"))), 2),
                call("CODE", null, new DevCodeDto(List.of(file("src/New.java", "xy"))), 6)));
        TaskGenContentResponse r = service.summarize(job("DEV"), calls);
        assertThat(r.blueprint().title()).isEqualTo("새 제목");
        assertThat(r.code().files()).extracting(f -> f.path()).containsExactly("src/New.java");
        assertThat(r.writing()).isNull();
        assertThat(r.answer()).isNull();
        assertThat(r.fillers()).isNull();
    }

    @Test
    @DisplayName("깨진 json 인 칸은 null 로 두고 오류를 내지 않는다(더 옛 결과가 읽히면 그것을 보인다). PENDING 호출은 무시")
    void brokenJson() {
        TaskGenCall pending = TaskGenCall.pendingBatch("p1", "j1", "hp", 0, "WRITING", null, "openai", "b1", T0.plusSeconds(600));
        List<TaskGenCall> calls = List.of(
                call("BLUEPRINT", null, "{깨짐", 1),
                call("CODE", null, new DevCodeDto(List.of(file("src/Ok.java", "x"))), 2),
                call("CODE", null, "[1,2", 3),
                call("ANSWER", null, "not json", 4),
                pending);
        TaskGenContentResponse r = service.summarize(job("DEV"), calls);
        assertThat(r.blueprint()).isNull();
        assertThat(r.answer()).isNull();
        assertThat(r.writing()).isNull();
        assertThat(r.code().files().get(0).path()).isEqualTo("src/Ok.java");
    }

    @Test
    @DisplayName("테스트 메서드 이름 뽑기: @Test(풀 이름 포함)가 붙은 것만, 다른 애너테이션·접근자와 섞여도")
    void testMethods() {
        assertThat(TaskGenContentService.testMethods("@Test\nvoid a() {}\n@org.junit.jupiter.api.Test\nvoid b() {}")).containsExactly("a", "b");
        assertThat(TaskGenContentService.testMethods("@Test @DisplayName(\"한글\")\n  public void 이름_테스트() {}\n @BeforeEach void setUp() {}")).containsExactly("이름_테스트");
        assertThat(TaskGenContentService.testMethods(null)).isEmpty();
        assertThat(TaskGenContentService.testMethods("class A {}")).isEmpty();
    }
}
