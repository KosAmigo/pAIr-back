package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 관문 평가(GateEvalLiveTest)의 로딩과 채점. 정답 세트(gold-final.json)와 이미 만들어 둔 과제 폴더를 읽고, 관문을 돌린 결과를 정답과 맞춰
 * 놓침·오탐·적중률을 센다. AI 호출은 Reviewer 로 빼 두어 모델 없이 시험할 수 있다. 과제를 만들지 않는다.
 */
final class GateEvalSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GateEvalSupport() {}

    /** 평가하는 관문. goldGate 는 정답 세트의 gate 이름. LEAK_ANY 는 두 눈 중 하나라도 건 것(실제 관문의 규칙) */
    enum EvalGate {
        LEAK_A("LEAK", TaskReviewService.Check.LEAK, Stage.REVIEW, true, true),
        LEAK_B("LEAK", TaskReviewService.Check.LEAK, Stage.REVIEW2, true, true),
        LEAK_ANY("LEAK", null, null, true, true),
        ANSWER_KEY("ANSWER_KEY", TaskReviewService.Check.ANSWER_KEY, Stage.REVIEW, false, true),
        BACKING("BACKING", TaskReviewService.Check.BACKING, Stage.REVIEW, true, false),
        CONSISTENCY("CONSISTENCY", TaskReviewService.Check.CONSISTENCY, Stage.REVIEW, true, true),
        /** 정답 키의 결정적 검사(단답형 검산, 다른 모델 풀이). 모델이 계산하지 않는 쪽. 과제 단위로 건다(인용 없음) */
        KEY_DET("ANSWER_KEY", null, null, false, true);

        final String goldGate;
        final TaskReviewService.Check check;
        final Stage stage;
        final boolean dev, biz;

        EvalGate(String goldGate, TaskReviewService.Check check, Stage stage, boolean dev, boolean biz) {
            this.goldGate = goldGate;
            this.check = check;
            this.stage = stage;
            this.dev = dev;
            this.biz = biz;
        }

        boolean taskLevel() {
            return this == KEY_DET;
        }

        boolean appliesTo(String track) {
            return "DEV".equals(track) ? dev : biz;
        }
    }

    /** 관문 하나를 한 번 돌린다(실제는 모델 호출, 시험은 가짜) */
    interface Reviewer {
        List<Violation> run(TaskReviewService.Check check, Stage stage, String input);
    }

    // ── 정답 세트 ─────────────────────────────

    record GoldItem(String gate, String quote, String location) {}

    record GoldTask(String kind, Path path, String track, int minutes, List<GoldItem> violations, List<GoldItem> uncertain) {}

    /** gold-final.json 을 읽는다. 과제 경로가 이 컴퓨터에 없으면 taskgen-runs 아래로 다시 찾는다 */
    static List<GoldTask> loadGold(Path goldFile) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(goldFile, StandardCharsets.UTF_8));
        List<GoldTask> out = new ArrayList<>();
        for (JsonNode t : root.path("tasks")) {
            out.add(new GoldTask(t.path("kind").asText("violation"), resolve(goldFile, t.path("path").asText()), t.path("track").asText(), t.path("minutes").asInt(20),
                    items(t.path("violations")), items(t.path("uncertain"))));
        }
        return out;
    }

    private static List<GoldItem> items(JsonNode arr) {
        List<GoldItem> out = new ArrayList<>();
        for (JsonNode v : arr) out.add(new GoldItem(v.path("gate").asText(""), v.path("quote").asText(""), v.path("location").asText("")));
        return out;
    }

    /** 저장된 절대 경로가 없으면 그 경로의 taskgen-runs 이하를 gold 폴더 위의 taskgen-runs 에서 찾는다 */
    static Path resolve(Path goldFile, String stored) {
        Path p = Path.of(stored);
        if (Files.isDirectory(p)) return p;
        String norm = stored.replace('\\', '/');
        int i = norm.indexOf("taskgen-runs/");
        if (i >= 0) {
            for (Path a = goldFile.toAbsolutePath().getParent(); a != null; a = a.getParent()) {
                if (a.getFileName() != null && a.getFileName().toString().equals("taskgen-runs")) return a.resolve(norm.substring(i + "taskgen-runs/".length()));
            }
        }
        return p;
    }

    // ── 과제 폴더 읽기 ─────────────────────────────

    record Loaded(String track, int minutes, BlueprintDto bp, DevDraftDto dev, BizDraftDto biz) {}

    /** 저장된 과제 폴더(task.json, files.json, questions.json, files/, report.json, request.json)를 관문 입력용 Draft 로 읽는다 */
    static Loaded loadTask(Path dir) throws IOException {
        JsonNode task = read(dir.resolve("task.json"));
        JsonNode filesJson = read(dir.resolve("files.json"));
        JsonNode questions = Files.exists(dir.resolve("questions.json")) ? read(dir.resolve("questions.json")) : JSON.createArrayNode();
        JsonNode report = Files.exists(dir.resolve("report.json")) ? read(dir.resolve("report.json")) : JSON.createObjectNode();
        JsonNode request = Files.exists(dir.resolve("request.json")) ? read(dir.resolve("request.json")) : JSON.createObjectNode();
        String track = task.path("track").asText();
        List<String> description = lines(task.path("description").asText(""));
        List<String> requirements = lines(task.path("requirements").asText(""));
        String followUp = "";
        for (JsonNode q : questions) if ("FOLLOWUP".equals(q.path("stage").asText()) && q.path("seq").asInt() == 3) followUp = q.path("content").asText("");
        BlueprintDto bp = blueprint(task, report, request, "BIZ".equals(track));
        Path root = dir.resolve("files");

        if ("DEV".equals(track)) {
            List<DevDraftDto.FileDraft> visible = new ArrayList<>(), hidden = new ArrayList<>(), solution = new ArrayList<>();
            String answer = "";
            for (JsonNode f : filesJson) {
                String path = f.path("path").asText();
                boolean isVisible = "VISIBLE".equals(f.path("visibility").asText());
                if (path.equals("answer.md")) answer = text(root.resolve(path));
                else if (path.startsWith("_gen/solution/")) solution.add(new DevDraftDto.FileDraft(path.substring("_gen/solution/".length()).replaceFirst("\\.txt$", ""), text(root.resolve(path))));
                else if (isVisible && !path.startsWith("_gen/")) visible.add(new DevDraftDto.FileDraft(path, text(root.resolve(path))));
                else if (!isVisible && path.endsWith(".java") && !path.startsWith("_gen/")) hidden.add(new DevDraftDto.FileDraft("test/" + path, text(root.resolve(path))));
            }
            DevDraftDto dev = new DevDraftDto(task.path("title").asText(), task.path("summary").asText(), task.path("code").asText(), task.path("category").asText(), description,
                    requirements, answer, visible, hidden, solution, task.path("difficulty").asText(), List.of(), List.of(), List.of(), followUp, List.of(), null, null);
            return new Loaded(track, task.path("timeLimitMin").asInt(20), bp, dev, null);
        }

        List<BizDraftDto.DocDraft> docs = new ArrayList<>();
        List<DevDraftDto.FileDraft> csvs = new ArrayList<>();
        for (JsonNode f : filesJson) {
            String path = f.path("path").asText();
            if (!"VISIBLE".equals(f.path("visibility").asText())) continue;
            if (path.endsWith(".pdf")) docs.add(new BizDraftDto.DocDraft(path, pdfPages(root.resolve(path))));
            else if (path.endsWith(".csv")) csvs.add(new DevDraftDto.FileDraft(path, text(root.resolve(path))));
        }
        List<BizDraftDto.QuestionDraft> qs = new ArrayList<>();
        List<String> followUps = new ArrayList<>();
        for (JsonNode q : questions) {
            if ("FOLLOWUP".equals(q.path("stage").asText())) {
                followUps.add(q.path("content").asText());
                continue;
            }
            List<BizDraftDto.Choice> choices = new ArrayList<>();
            for (JsonNode c : q.path("choices")) choices.add(new BizDraftDto.Choice(c.path("content").asText(), c.path("correct").asBoolean()));
            List<String> accepted = new ArrayList<>();
            for (JsonNode a : q.path("accepted")) accepted.add(a.asText());
            BizDraftDto.Type type = "SINGLE".equals(q.path("type").asText()) ? BizDraftDto.Type.SINGLE : BizDraftDto.Type.SHORT;
            List<BizDraftDto.Source> sources = new ArrayList<>();
            for (JsonNode sr : q.path("sources")) sources.add(new BizDraftDto.Source(sr.path("path").asText(), sr.path("pages").isNull() ? "" : sr.path("pages").asText("")));
            qs.add(new BizDraftDto.QuestionDraft(type, q.path("content").asText(), choices, accepted, q.path("calc").asText(""), sources));
        }
        JsonNode tr = report.path("details").path("trap");
        BizDraftDto.Trap trap = new BizDraftDto.Trap(tr.path("description").asText(""), tr.path("evidence").asText(""), tr.path("correctConclusion").asText(""), tr.path("wrongConclusion").asText(""));
        BizDraftDto biz = new BizDraftDto(task.path("title").asText(), task.path("summary").asText(), task.path("code").asText(), task.path("category").asText(), description,
                requirements, followUps, docs, csvs, text(root.resolve("checklist.md")), trap, qs);
        return new Loaded(track, task.path("timeLimitMin").asInt(20), bp, null, biz);
    }

    /** 설계도는 저장돼 있지 않아, report.json 에 남은 숨은 결함·판단 지점·결론과 요청의 함정 문장으로 관문 입력에 필요한 만큼만 채운다 */
    private static BlueprintDto blueprint(JsonNode task, JsonNode report, JsonNode request, boolean biz) {
        JsonNode details = report.path("details");
        List<BlueprintDto.Flaw> flaws = new ArrayList<>();
        for (JsonNode f : details.path("hiddenFlaws")) flaws.add(new BlueprintDto.Flaw(f.path("description").asText(), f.path("verifyWith").asText(""), "", f.path("caughtBy").asText("")));
        if (flaws.isEmpty() && !request.path("trap").asText("").isBlank()) flaws.add(new BlueprintDto.Flaw(request.path("trap").asText(), "", "", ""));
        List<BlueprintDto.Judgment> judgments = new ArrayList<>();
        for (JsonNode j : details.path("judgmentPoints")) {
            List<BlueprintDto.Option> options = new ArrayList<>();
            for (JsonNode o : j.path("options")) options.add(new BlueprintDto.Option(o.asText(), ""));
            judgments.add(new BlueprintDto.Judgment(j.path("question").asText(), j.path("basis").asText(""), options));
        }
        BlueprintDto.Conclusion conclusion = null;
        if (biz) conclusion = new BlueprintDto.Conclusion(details.path("trap").path("correctConclusion").asText(""), details.path("trap").path("wrongConclusion").asText(""));
        return new BlueprintDto(task.path("title").asText(), task.path("summary").asText(), task.path("code").asText(), task.path("category").asText(), List.of(),
                task.path("summary").asText(), List.of(), List.of(), List.of(), flaws, judgments, List.of(), conclusion, List.of(), List.of(), List.of(), List.of());
    }

    private static JsonNode read(Path p) throws IOException {
        return JSON.readTree(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static String text(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    private static List<String> lines(String s) {
        return s.isBlank() ? List.of() : List.of(s.split("\\R"));
    }

    private static List<String> pdfPages(Path p) throws IOException {
        List<String> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(p))) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(stripper.getText(doc).strip());
            }
        }
        return pages;
    }

    // ── 관문 실행 ─────────────────────────────

    /** 과제 하나에 평가 관문을 돌려, 관문별로 건 위반(Q 제외)을 모은다. 이 직군에 안 맞는 관문은 넣지 않는다 */
    static Map<EvalGate, List<Violation>> runGates(Loaded t, Reviewer reviewer) {
        return runGates(t, reviewer, x -> List.of());
    }

    /** deterministic: 모델이 계산하지 않는 정답 키 검사(BIZ 만). 실패한 검사를 위반 하나씩으로 돌려준다 */
    static Map<EvalGate, List<Violation>> runGates(Loaded t, Reviewer reviewer, java.util.function.Function<Loaded, List<Violation>> deterministic) {
        Map<EvalGate, List<Violation>> out = new EnumMap<>(EvalGate.class);
        TaskGenRequest form = new TaskGenRequest(t.track(), "", "", "SELF", t.minutes());
        for (EvalGate g : EvalGate.values()) {
            if (g.check == null || !g.appliesTo(t.track())) continue;
            String input = "DEV".equals(t.track()) ? TaskReviewService.devInput(g.check, form, t.bp(), t.dev()) : TaskReviewService.bizInput(g.check, form, t.bp(), t.biz());
            if (input == null) continue;
            out.put(g, reviewer.run(g.check, g.stage, input).stream().filter(Violation::mustFix).toList());
        }
        if ("BIZ".equals(t.track())) out.put(EvalGate.KEY_DET, deterministic.apply(t));
        if (out.containsKey(EvalGate.LEAK_A) || out.containsKey(EvalGate.LEAK_B)) {
            List<Violation> any = new ArrayList<>(out.getOrDefault(EvalGate.LEAK_A, List.of()));
            any.addAll(out.getOrDefault(EvalGate.LEAK_B, List.of()));
            out.put(EvalGate.LEAK_ANY, any);
        }
        return out;
    }

    // ── 채점 ─────────────────────────────

    /** 인용이 같은 곳을 가리키는가: 공백을 뺀 글이 서로 들어 있거나 글자 3개 묶음이 많이 겹친다 */
    static boolean sameQuote(String a, String b) {
        String x = GenTextService.norm(a), y = GenTextService.norm(b);
        if (x.length() < 6 || y.length() < 6) return false;
        if (x.contains(y) || y.contains(x)) return true;
        Set<String> gx = grams(x), gy = grams(y);
        if (gx.isEmpty() || gy.isEmpty()) return false;
        int common = 0;
        for (String g : gx) if (gy.contains(g)) common++;
        return 2.0 * common / (gx.size() + gy.size()) >= 0.6;
    }

    private static final java.util.regex.Pattern GOLD_Q = java.util.regex.Pattern.compile("(?:\\bQ|seq\\s*)(\\d+)");
    private static final java.util.regex.Pattern FLAG_Q = java.util.regex.Pattern.compile("(?:question|choice):(\\d+)");

    /** 정답 키 위반은 같은 문항을 가리키면 같은 곳으로 본다(인용이 정답 세트와 달라도) */
    static boolean sameQuestion(String flagLocation, String goldLocation) {
        var f = FLAG_Q.matcher(flagLocation == null ? "" : flagLocation);
        var g = GOLD_Q.matcher(goldLocation == null ? "" : goldLocation);
        return f.find() && g.find() && f.group(1).equals(g.group(1));
    }

    private static boolean matches(EvalGate g, Violation f, GoldItem item) {
        return sameQuote(f.quote(), item.quote()) || (g == EvalGate.ANSWER_KEY && sameQuestion(f.location(), item.location()));
    }

    private static Set<String> grams(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        return out;
    }

    /** 한 과제·한 관문의 채점. hits/misses 는 정답 위반 기준, falsePositives 는 건 것 중 정답도 uncertain 도 아닌 것 */
    record TaskScore(String path, String kind, EvalGate gate, int gold, List<GoldItem> misses, int hits, List<Violation> falsePositives, int uncertainFlags) {}

    static TaskScore score(GoldTask t, EvalGate g, List<Violation> flags) {
        List<GoldItem> gold = t.violations().stream().filter(v -> v.gate().equals(g.goldGate)).toList();
        List<GoldItem> uncertain = t.uncertain().stream().filter(v -> v.gate().isBlank() || v.gate().equals(g.goldGate)).toList();
        List<GoldItem> misses = new ArrayList<>();
        int hits = 0;
        if (g.taskLevel()) { // 인용이 없는 검사: 걸렸으면 이 관문의 정답 위반을 모두 잡은 것으로, 정답이 없는데 걸렸으면 오탐 1건으로 센다
            if (!flags.isEmpty() && !gold.isEmpty()) return new TaskScore(t.path().toString(), t.kind(), g, gold.size(), List.of(), gold.size(), List.of(), 0);
            if (flags.isEmpty()) return new TaskScore(t.path().toString(), t.kind(), g, gold.size(), gold, 0, List.of(), 0);
            return new TaskScore(t.path().toString(), t.kind(), g, 0, List.of(), 0, flags.subList(0, 1), 0);
        }
        for (GoldItem item : gold) {
            if (flags.stream().anyMatch(f -> matches(g, f, item))) hits++;
            else misses.add(item);
        }
        List<Violation> fps = new ArrayList<>();
        int uncertainFlags = 0;
        for (Violation f : flags) {
            if (gold.stream().anyMatch(item -> matches(g, f, item))) continue;
            if (uncertain.stream().anyMatch(item -> matches(g, f, item))) {
                uncertainFlags++; // 오탐도 적중도 아니다
                continue;
            }
            fps.add(f);
        }
        return new TaskScore(t.path().toString(), t.kind(), g, gold.size(), misses, hits, fps, uncertainFlags);
    }

    /** 관문·종류별 합계 */
    record Total(int tasks, int gold, int hits, int misses, int falsePositives, int uncertainFlags) {
        double hitRate() {
            return gold == 0 ? Double.NaN : (double) hits / gold;
        }
    }

    static Map<String, Map<EvalGate, Total>> totals(List<TaskScore> scores) {
        Map<String, Map<EvalGate, Total>> out = new LinkedHashMap<>();
        for (String kind : List.of("planted", "clean", "violation")) out.put(kind, new EnumMap<>(EvalGate.class));
        for (TaskScore s : scores) {
            Total o = out.computeIfAbsent(s.kind(), k -> new EnumMap<>(EvalGate.class)).getOrDefault(s.gate(), new Total(0, 0, 0, 0, 0, 0));
            out.get(s.kind()).put(s.gate(), new Total(o.tasks() + 1, o.gold() + s.gold(), o.hits() + s.hits(), o.misses() + s.misses().size(),
                    o.falsePositives() + s.falsePositives().size(), o.uncertainFlags() + s.uncertainFlags()));
        }
        return out;
    }

    /** 결과 글: 핵심 숫자(planted 놓침, clean 오탐) → 종류별 표 → 놓침·오탐 목록 */
    static String markdown(List<TaskScore> scores, int taskCount) {
        var totals = totals(scores);
        StringBuilder md = new StringBuilder("# 관문 평가 결과\n\n과제 ").append(taskCount).append("개에 관문만 돌렸다(생성 없음). uncertain 은 오탐도 적중도 아니다.\n\n");
        md.append("## 핵심\n\n");
        for (EvalGate g : EvalGate.values()) {
            Total p = totals.get("planted").get(g), c = totals.get("clean").get(g);
            md.append("- ").append(g).append(": planted 놓침 ").append(p == null ? "-" : p.misses() + "/" + p.gold()).append(", clean 오탐 ").append(c == null ? "-" : String.valueOf(c.falsePositives())).append('\n');
        }
        for (var kind : totals.entrySet()) {
            if (kind.getValue().isEmpty()) continue;
            md.append("\n## ").append(kind.getKey()).append("\n\n| 관문 | 과제 | 정답 위반 | 적중 | 놓침 | 오탐 | 적중률 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
            for (var e : kind.getValue().entrySet()) {
                Total t = e.getValue();
                md.append("| ").append(e.getKey()).append(" | ").append(t.tasks()).append(" | ").append(t.gold()).append(" | ").append(t.hits()).append(" | ").append(t.misses()).append(" | ")
                        .append(t.falsePositives()).append(" | ").append(Double.isNaN(t.hitRate()) ? "-" : Math.round(t.hitRate() * 100) + "%").append(" |\n");
            }
        }
        md.append("\n## 놓침 (정답에 있는데 관문이 통과시킨 것)\n\n");
        for (TaskScore s : scores) for (GoldItem m : s.misses()) md.append("- [").append(s.kind()).append("] ").append(s.gate()).append(' ').append(shortPath(s.path())).append(": ").append(clip(m.quote())).append(" (").append(m.location()).append(")\n");
        md.append("\n## 오탐 (관문이 걸었는데 정답에 없는 것)\n\n");
        for (TaskScore s : scores) for (Violation f : s.falsePositives()) md.append("- [").append(s.kind()).append("] ").append(s.gate()).append(' ').append(shortPath(s.path())).append(": ").append(clip(f.quote())).append(" → ").append(clip(f.fix())).append('\n');
        return md.toString();
    }

    private static String shortPath(String p) {
        String n = p.replace('\\', '/');
        String[] parts = n.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : n;
    }

    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 100 ? one.substring(0, 100) + "…" : one;
    }
}
