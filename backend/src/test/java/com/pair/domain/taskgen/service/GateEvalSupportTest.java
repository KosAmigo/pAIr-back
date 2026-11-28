package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.service.GateEvalSupport.EvalGate;
import com.pair.domain.taskgen.service.GateEvalSupport.GoldItem;
import com.pair.domain.taskgen.service.GateEvalSupport.GoldTask;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 관문 평가의 정답 세트·과제 폴더 읽기와 채점을 AI 없이 확인한다(모델 호출은 가짜) */
class GateEvalSupportTest {

    private static final String LEAK_QUOTE = "월말에서 다음 달로 날짜를 옮길 때 말일이 유지되도록 마지막 날짜를 기준으로 계산해야 합니다.";
    private static final String OTHER_QUOTE = "청구 예정표를 조회한 뒤 지정일까지의 일정을 반환하세요 그대로 쓰면 됩니다.";

    private static GoldTask gold(String kind, List<GoldItem> violations, List<GoldItem> uncertain) {
        return new GoldTask(kind, Path.of("x/run/01-task"), "DEV", 20, violations, uncertain);
    }

    private static Violation flag(String quote) {
        return new Violation("M5", quote, "description", "고친다");
    }

    @Test
    @DisplayName("정답 세트(gold-final.json 모양)를 읽고, 과제 경로가 이 컴퓨터에 없으면 taskgen-runs 아래로 다시 찾는다")
    void loadsGoldAndResolvesPaths(@TempDir Path tmp) throws Exception {
        Path runs = tmp.resolve("taskgen-runs");
        Path task = runs.resolve("2026-11-25-r2/01-a");
        Files.createDirectories(task);
        Path goldDir = runs.resolve("_verify/gold");
        Files.createDirectories(goldDir);
        Files.writeString(goldDir.resolve("gold-final.json"), """
                {"tasks":[
                 {"kind":"violation","path":"C:\\\\Other\\\\taskgen-runs\\\\2026-11-25-r2\\\\01-a","track":"DEV","minutes":40,
                  "violations":[{"gate":"LEAK","item":"M5","quote":"인용 하나","location":"task.json description 줄 1"}],
                  "uncertain":[{"gate":"BACKING","item":"M2","quote":"애매한 것","location":"x"}]},
                 {"kind":"clean","path":"%s","track":"BIZ","minutes":20,"violations":[],"uncertain":[]}]}
                """.formatted(task.toString().replace("\\", "\\\\")), StandardCharsets.UTF_8);

        List<GoldTask> tasks = GateEvalSupport.loadGold(goldDir.resolve("gold-final.json"));

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).kind()).isEqualTo("violation");
        assertThat(tasks.get(0).path()).isEqualTo(task); // 저장된 C:\Other\... 경로를 gold 폴더 위의 taskgen-runs 로 다시 찾음
        assertThat(tasks.get(0).minutes()).isEqualTo(40);
        assertThat(tasks.get(0).violations()).singleElement().satisfies(v -> assertThat(v.gate()).isEqualTo("LEAK"));
        assertThat(tasks.get(0).uncertain()).extracting(GoldItem::gate).containsExactly("BACKING");
        assertThat(tasks.get(1).kind()).isEqualTo("clean");
        assertThat(tasks.get(1).violations()).isEmpty();
    }

    @Test
    @DisplayName("저장된 DEV 과제 폴더를 관문 입력용 Draft 로 읽는다: 보이는 파일·숨은 테스트·정답 풀이·후속 질문, 숨은 결함은 report.json 에서")
    void loadsDevTaskFolder(@TempDir Path tmp) throws Exception {
        Path files = tmp.resolve("files");
        Files.createDirectories(files.resolve("src/shop"));
        Files.createDirectories(files.resolve("shop"));
        Files.createDirectories(files.resolve("_gen/solution/src/shop"));
        Files.writeString(files.resolve("src/shop/Cart.java"), "class Cart {}", StandardCharsets.UTF_8);
        Files.writeString(files.resolve("shop/CartHiddenTest.java"), "class CartHiddenTest {}", StandardCharsets.UTF_8);
        Files.writeString(files.resolve("answer.md"), "정답 설명", StandardCharsets.UTF_8);
        Files.writeString(files.resolve("_gen/solution/src/shop/Cart.java.txt"), "class Cart { int fixed; }", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("task.json"), "{\"track\":\"DEV\",\"title\":\"장바구니\",\"summary\":\"요약\",\"code\":\"cart\",\"category\":\"기능 구현\",\"timeLimitMin\":20,"
                + "\"description\":\"첫 줄\\n둘째 줄\",\"requirements\":\"a → 1\\nb → 2\"}", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("files.json"), "[{\"path\":\"src/shop/Cart.java\",\"visibility\":\"VISIBLE\"},{\"path\":\"shop/CartHiddenTest.java\",\"visibility\":\"HIDDEN\"},"
                + "{\"path\":\"answer.md\",\"visibility\":\"HIDDEN\"},{\"path\":\"_gen/solution/src/shop/Cart.java.txt\",\"visibility\":\"HIDDEN\"}]", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("questions.json"), "[{\"stage\":\"FOLLOWUP\",\"seq\":3,\"content\":\"맞춤 후속 질문\"}]", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("report.json"), "{\"details\":{\"hiddenFlaws\":[{\"description\":\"합계가 어긋난다\",\"verifyWith\":\"숨은 테스트\",\"caughtBy\":\"sumExact\"}]}}", StandardCharsets.UTF_8);

        var loaded = GateEvalSupport.loadTask(tmp);

        assertThat(loaded.track()).isEqualTo("DEV");
        assertThat(loaded.dev().description()).containsExactly("첫 줄", "둘째 줄"); // 줄 번호가 정답 세트의 '줄 N' 과 같다
        assertThat(loaded.dev().files()).extracting(f -> f.path()).containsExactly("src/shop/Cart.java");
        assertThat(loaded.dev().hiddenTests()).extracting(f -> f.path()).containsExactly("test/shop/CartHiddenTest.java");
        assertThat(loaded.dev().solution()).extracting(f -> f.path()).containsExactly("src/shop/Cart.java");
        assertThat(loaded.dev().taskFollowUp()).isEqualTo("맞춤 후속 질문");
        assertThat(loaded.bp().flaws()).singleElement().satisfies(f -> assertThat(f.what()).isEqualTo("합계가 어긋난다"));

        // 관문 입력: 답 노출은 결함+보이는 글, 테스트 근거는 숨은 테스트. BIZ 전용 관문(정답 키)은 돌리지 않는다
        List<TaskReviewService.Check> checks = new ArrayList<>();
        var out = GateEvalSupport.runGates(loaded, (check, stage, input) -> {
            checks.add(check);
            return check == TaskReviewService.Check.LEAK ? List.of(flag("첫 줄")) : List.of();
        });
        assertThat(checks).containsExactly(TaskReviewService.Check.LEAK, TaskReviewService.Check.LEAK, TaskReviewService.Check.BACKING, TaskReviewService.Check.CONSISTENCY);
        assertThat(out).containsKeys(EvalGate.LEAK_A, EvalGate.LEAK_B, EvalGate.LEAK_ANY, EvalGate.BACKING, EvalGate.CONSISTENCY).doesNotContainKey(EvalGate.ANSWER_KEY);
        assertThat(out.get(EvalGate.LEAK_ANY)).hasSize(2); // 두 눈이 각각 건 것의 합
    }

    @Test
    @DisplayName("채점: 정답과 같은 인용을 걸면 적중, 못 걸면 놓침, 정답에도 uncertain 에도 없는 것을 걸면 오탐. uncertain 은 오탐도 적중도 아니다")
    void scoring() {
        var goldItems = List.of(new GoldItem("LEAK", LEAK_QUOTE, "description 줄 1"), new GoldItem("LEAK", OTHER_QUOTE, "requirements 줄 2"));
        var uncertain = List.of(new GoldItem("LEAK", "경계 사례로 표시된 애매한 문장입니다 확인 필요", "x"));
        var t = gold("violation", goldItems, uncertain);

        var s = GateEvalSupport.score(t, EvalGate.LEAK_A, List.of(flag(LEAK_QUOTE.substring(0, 30)), // 일부만 인용해도 같은 곳
                flag("경계 사례로 표시된 애매한 문장입니다 확인 필요"), flag("완전히 다른 문장에 대한 지적입니다")));

        assertThat(s.gold()).isEqualTo(2);
        assertThat(s.hits()).isEqualTo(1);
        assertThat(s.misses()).extracting(GoldItem::quote).containsExactly(OTHER_QUOTE);
        assertThat(s.falsePositives()).extracting(Violation::quote).containsExactly("완전히 다른 문장에 대한 지적입니다");
        assertThat(s.uncertainFlags()).isEqualTo(1);

        // 게이트 이름이 다른 정답은 이 관문의 정답이 아니다: BACKING 관문으로 보면 LEAK 정답은 없고 걸린 것은 모두 오탐
        var other = GateEvalSupport.score(t, EvalGate.BACKING, List.of(flag(LEAK_QUOTE)));
        assertThat(other.gold()).isZero();
        assertThat(other.falsePositives()).hasSize(1);

        assertThat(GateEvalSupport.sameQuote(LEAK_QUOTE, "  " + LEAK_QUOTE.replace(" ", "") + "  ")).isTrue();
        assertThat(GateEvalSupport.sameQuote("짧", "짧")).isFalse();
    }

    @Test
    @DisplayName("종류별 합계와 결과 글: planted 놓침과 clean 오탐이 핵심 줄에 나오고, 깨끗한 과제에서 건 것은 모두 오탐이다")
    void totalsAndMarkdown() {
        var planted = gold("planted", List.of(new GoldItem("LEAK", LEAK_QUOTE, "description 줄 1")), List.of());
        var clean = gold("clean", List.of(), List.of());
        List<GateEvalSupport.TaskScore> scores = new ArrayList<>();
        scores.add(GateEvalSupport.score(planted, EvalGate.LEAK_A, List.of())); // 놓침
        scores.add(GateEvalSupport.score(planted, EvalGate.LEAK_B, List.of(flag(LEAK_QUOTE)))); // 적중
        scores.add(GateEvalSupport.score(clean, EvalGate.LEAK_A, List.of(flag("깨끗한 과제인데 건 문장입니다"), flag("또 건 문장입니다 두 번째")))); // 오탐 2
        scores.add(GateEvalSupport.score(clean, EvalGate.LEAK_B, List.of()));

        var totals = GateEvalSupport.totals(scores);
        assertThat(totals.get("planted").get(EvalGate.LEAK_A).misses()).isEqualTo(1);
        assertThat(totals.get("planted").get(EvalGate.LEAK_B).hitRate()).isEqualTo(1.0);
        assertThat(totals.get("clean").get(EvalGate.LEAK_A).falsePositives()).isEqualTo(2);
        assertThat(totals.get("clean").get(EvalGate.LEAK_B).falsePositives()).isZero();

        String md = GateEvalSupport.markdown(scores, 2);
        assertThat(md).contains("- LEAK_A: planted 놓침 1/1, clean 오탐 2").contains("- LEAK_B: planted 놓침 0/1, clean 오탐 0")
                .contains("## planted").contains("## clean").contains("100%").contains("## 놓침").contains("## 오탐").contains("깨끗한 과제인데 건 문장입니다");
    }
}
