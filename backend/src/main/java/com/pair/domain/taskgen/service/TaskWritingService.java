package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Random;

/**
 * 2단계 작성의 입력 만들기와 결과 확인. 정보 가리기가 이 설계의 핵심이라(설계 3장) 작성 입력은 여기서만 만든다:
 * 사실 목록(결함·판단 근거·배경이 섞인 채 자리별로 섞음), 예 목록, API·문서 계획·CSV 표(모두 응시자에게 보이는 것), 정해야 할 질문만 준다.
 * 결함(what·where·evidence·caughtBy), 판단 선택지와 근거 연결, 함정 핵심어, 결론, 문항 계획은 주지 않는다.
 */
final class TaskWritingService {

    private TaskWritingService() {}

    /**
     * 설명(description)에서 요구사항 줄과 똑같은 줄을 뺀다(g12·g13 판정 Q2: 작성 모델이 예를 요구사항과 설명 끝에 두 번 씀).
     * 줄 단위로 띄어쓰기·문장부호를 무시하고 같을 때만 빼며, 빈 문단은 남기지 않는다
     */
    static List<String> withoutRequirementLines(List<String> description, List<String> requirements) {
        java.util.Set<String> req = new java.util.HashSet<>();
        for (String r : requirements == null ? List.<String>of() : requirements) {
            for (String line : r.split("\n")) {
                String n = GenTextService.norm(line);
                if (!n.isEmpty()) req.add(n);
            }
        }
        if (req.isEmpty() || description == null) return description;
        List<String> out = new ArrayList<>();
        for (String p : description) {
            if (p == null) continue;
            String kept = String.join("\n", java.util.Arrays.stream(p.split("\n")).filter(l -> !req.contains(GenTextService.norm(l))).toList()).strip();
            if (!kept.isEmpty()) out.add(kept);
        }
        return out;
    }

    /** 작성 단계 입력. feedback 은 다시 쓸 때만(writerFeedback 으로 만든, 결함 정보가 없는 글) */
    static String input(BlueprintDto b, boolean biz, int minutes, String feedback) {
        StringBuilder sb = new StringBuilder("제한 시간: ").append(minutes).append("분\n상황: ").append(b.scenario()).append('\n');
        if (!b.api().isEmpty()) sb.append("공개 API:\n").append(lines(b.api()));
        List<String> questions = b.judgments().stream().map(BlueprintDto.Judgment::question).toList();
        if (!questions.isEmpty()) sb.append("응시자가 정해야 할 것(요청 문장에만 쓴다):\n").append(lines(questions));
        if (biz) {
            sb.append("문서 계획:\n").append(lines(b.docs().stream().map(d -> d.path() + " (" + d.role() + ")").toList()));
            for (var t : b.tables()) {
                sb.append("CSV ").append(t.path()).append(":\n").append(String.join(",", t.columns())).append('\n');
                t.rows().forEach(r -> sb.append(String.join(",", r)).append('\n'));
            }
        }
        Random random = new Random(String.valueOf(b.code()).hashCode());
        Map<String, List<String>> byPlace = new LinkedHashMap<>();
        for (var f : b.facts()) byPlace.computeIfAbsent(f.place(), k -> new ArrayList<>()).add(f.text());
        byPlace.forEach((place, texts) -> {
            List<String> mixed = new ArrayList<>(texts);
            Collections.shuffle(mixed, random);
            sb.append("사실 목록 [place=").append(place).append("]:\n").append(lines(mixed));
        });
        if (!b.examples().isEmpty()) {
            List<String> mixed = new ArrayList<>(b.examples().stream().map(e -> e.input() + " → " + e.expected()).toList());
            Collections.shuffle(mixed, random);
            sb.append("예 목록:\n").append(lines(mixed));
        }
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    /** BIZ 작성 ① 요청 글 입력: 정해야 할 것, 자료 이름, place=description 사실만(문서 자리 사실·CSV 값은 주지 않는다, g6) */
    static String bizBriefInput(BlueprintDto b, int minutes, String feedback) {
        StringBuilder sb = new StringBuilder("제한 시간: ").append(minutes).append("분\n상황: ").append(b.scenario()).append('\n');
        List<String> questions = b.judgments().stream().map(BlueprintDto.Judgment::question).toList();
        if (!questions.isEmpty()) sb.append("응시자가 정해야 할 것(요청 문장에만 쓴다):\n").append(lines(questions));
        List<String> names = new ArrayList<>(b.docs().stream().map(d -> d.path() + " (" + d.role() + ")").toList());
        b.tables().forEach(t -> names.add(t.path() + " (열: " + String.join(", ", t.columns()) + ")"));
        sb.append("자료 이름:\n").append(lines(names));
        appendFacts(sb, b, BlueprintService.DESCRIPTION);
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    /** BIZ 작성 ② 문서 하나의 입력: 이 문서 경로·역할, 다른 자료 이름, CSV 표, 이 문서 자리의 사실만. mark 는 문서 호출 표시(가짜 AI 가 가른다) */
    static String bizDocInput(BlueprintDto b, BlueprintDto.Doc doc, int minutes, String feedback, String mark) {
        StringBuilder sb = new StringBuilder(mark).append("\n제한 시간: ").append(minutes).append("분\n상황: ").append(b.scenario()).append('\n');
        sb.append("이 문서: ").append(doc.path()).append(" (").append(doc.role()).append(")\n");
        List<String> others = new ArrayList<>();
        b.docs().stream().filter(d -> !d.path().equals(doc.path())).forEach(d -> others.add(d.path()));
        b.tables().forEach(t -> others.add(t.path()));
        if (!others.isEmpty()) sb.append("함께 있는 다른 자료(내용을 지어내지 않는다):\n").append(lines(others));
        for (var t : b.tables()) {
            sb.append("CSV ").append(t.path()).append(":\n").append(String.join(",", t.columns())).append('\n');
            t.rows().forEach(r -> sb.append(String.join(",", r)).append('\n'));
        }
        appendFacts(sb, b, doc.path());
        if (feedback != null && !feedback.isBlank()) sb.append('\n').append(feedback);
        return sb.toString();
    }

    /** place 자리의 사실을 섞어 붙인다(같은 설계도면 같은 순서) */
    private static void appendFacts(StringBuilder sb, BlueprintDto b, String place) {
        List<String> mine = new ArrayList<>(b.facts().stream().filter(f -> place.equals(f.place())).map(BlueprintDto.Fact::text).toList());
        Collections.shuffle(mine, new Random((String.valueOf(b.code()) + place).hashCode()));
        sb.append("사실 목록 [place=").append(place).append("]:\n").append(mine.isEmpty() ? "(없음)\n" : lines(mine));
    }

    private static String lines(List<String> l) {
        StringBuilder sb = new StringBuilder();
        l.forEach(s -> sb.append("- ").append(s).append('\n'));
        return sb.toString();
    }

    /** 사실 목록의 문장과 예의 입력·기대 출력이 글에 그대로 들어갔는지(M4). examplesText 는 요구사항(DEV)에서 찾는다 */
    static GenCheckResultDto factsCheck(BlueprintDto b, String corpus, String examplesText) {
        String ex = GenTextService.norm(examplesText);
        List<String> missing = new ArrayList<>();
        for (var f : b.facts()) if (!GenTextService.containsLike(corpus, f.text())) missing.add(f.text());
        for (var e : b.examples()) {
            if (!ex.contains(GenTextService.norm(e.input())) || !ex.contains(GenTextService.norm(e.expected()))) missing.add(e.input() + " → " + e.expected());
        }
        if (missing.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FACTS_CHECK, true, "사실 " + b.facts().size() + "개와 예 " + b.examples().size() + "개가 글에 그대로 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.FACTS_CHECK, false, "글에 그대로 들어가지 않은 사실·예가 있습니다: " + String.join(" / ", missing.subList(0, Math.min(5, missing.size()))));
    }

    /**
     * 사례(case)마다 그 기대 결과를 정하는 보이는 글의 문장(basis)이 작성 후 글에 실제로 있는지(띄어쓰기·문장부호 차이는 무시).
     * 없으면 그 사례의 기대 결과를 응시자가 자료로 알 수 없다(M2): 작성 단계를 다시 돌려 문장을 넣게 한다
     */
    static GenCheckResultDto basisCheck(BlueprintDto b, String visibleText) {
        List<String> missing = new ArrayList<>();
        for (var c : b.cases()) {
            if (c.basis() == null || c.basis().isBlank()) missing.add(c.name() + ": 근거 문장(basis)이 없음");
            else if (!GenTextService.containsLike(visibleText, c.basis())) missing.add(c.name() + ": " + c.basis());
        }
        if (missing.isEmpty()) return new GenCheckResultDto(TaskPipelineService.BASIS_CHECK, true, "사례 " + b.cases().size() + "개의 근거 문장이 모두 글에 그대로 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.BASIS_CHECK, false, "사례의 근거 문장이 글에 없습니다: " + String.join(" / ", missing.subList(0, Math.min(5, missing.size()))));
    }

    /**
     * 작성 단계를 다시 돌릴 때 붙이는 안내. 검사 상세에는 결함·핵심어가 들어 있을 수 있어 그대로 주지 않는다:
     * 검사 이름에 맞는 일반 규칙과, 작성 단계가 이전에 쓴 글 중 상세에 인용된 문장만 준다.
     */
    static String writerFeedback(List<GenCheckResultDto> failed, String previousText) {
        if (failed == null || failed.isEmpty()) return ""; // 이어 만들기(출력 한도 끊김)처럼 고칠 검사가 없으면 안내도 없다
        StringBuilder sb = new StringBuilder("앞서 쓴 글이 확인에서 아래 문제로 걸렸습니다. 같은 사실 목록으로 처음부터 다시 쓰세요.\n");
        for (GenCheckResultDto c : failed) {
            sb.append("- ").append(c.name()).append(": ").append(rule(c.name())).append('\n');
            // 숫자·쪽·경로를 말하는 상세는 결함 정보가 아니니 그대로 준다(어느 숫자·어느 쪽인지 알아야 고친다)
            if (c.name().equals(BizNumbersService.NAME) || c.name().equals(BizCheckService.FORMAT)) sb.append("  상세: ").append(c.detail()).append('\n');
            String detail = GenTextService.norm(c.detail());
            int shown = 0;
            for (String s : sentences(previousText)) {
                String n = GenTextService.norm(s);
                if (n.length() >= 10 && detail.contains(n) && shown++ < 5) sb.append("  고칠 문장: ").append(s).append('\n');
            }
        }
        return sb.toString();
    }

    static final String FEATURE_LINES_CHECK = "요구사항에 만들 기능 설명이 있음";
    private static final Pattern FEATURE_LINE = Pattern.compile("^\\s*만들 기능\\s*:");

    /**
     * 추가 메서드마다 요구사항에 "만들 기능: `시그니처` — …" 줄이 있는지(2026-11-27: 예시만 있으면 숫자 뜻을 알 수 없었다).
     * 새 검사라 경고로만 알린다(다시 만들지 않는다)
     */
    static GenCheckResultDto featureLinesCheck(BlueprintDto bp, List<String> requirements) {
        List<String> missing = new ArrayList<>();
        List<String> lines = requirements == null ? List.of() : requirements.stream().flatMap(r -> java.util.Arrays.stream(r.split("\n"))).toList();
        for (String api : bp.api() == null ? List.<String>of() : bp.api()) {
            if (api == null || !api.strip().startsWith("추가:")) continue;
            var m = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(").matcher(api);
            if (!m.find()) continue;
            Pattern call = Pattern.compile("(?<![\\w])" + Pattern.quote(m.group(1)) + "\\s*\\(");
            if (lines.stream().noneMatch(l -> FEATURE_LINE.matcher(l).find() && call.matcher(l).find())) missing.add(m.group(1));
        }
        return missing.isEmpty() ? new GenCheckResultDto(FEATURE_LINES_CHECK, true, "추가 메서드마다 만들 기능 줄이 있습니다")
                : new GenCheckResultDto(FEATURE_LINES_CHECK, false, "요구사항에 '만들 기능:' 줄이 없는 추가 메서드: " + missing, GenCheckResultDto.WARN);
    }

    private static String rule(String check) {
        if (check.equals(TaskPipelineService.M6_NEEDED_CHECK)) return "notes 가 너무 길다. 제목 한 줄과 주어진 기록만 한 줄씩 쓰고, 배경 문단·요약·덧붙인 기록은 빼서 지난번의 절반 정도로 짧게 쓴다.";
        if (check.equals(TaskPipelineService.BASIS_CHECK)) return "사실 목록의 문장을 하나도 빼지 말고 그대로 넣는다(사례의 근거 문장도 사실 목록에 있다).";
        if (check.equals(FeatureCheckService.NAME)) return "시작 코드에 이미 있는 메서드는 추가·구현하라고 쓰지 않는다. 공개 API 목록에서 '추가:' 로 적힌 것만 새로 만들라고 쓴다.";
        if (check.equals(DevCheckService.FOLLOW_UP)) return "후속 질문에는 이 과제 고유의 명사·용어·값을 쓰지 말고, 무엇을 어떻게 검증했는지 묻는 일반 틀로만 쓴다.";
        if (check.equals(TaskPipelineService.MENTION_CHECK)) return "보이는 파일에 없는 파일 이름은 쓰지 않는다. 있는 자료 이름만 쓴다.";
        if (check.equals(TaskPipelineService.META_CHECK)) return "문서는 실제 업무 문서(메모, 로그, 보고서)처럼 쓴다. 과제, 응시자, AI, 기획안 양식을 말하는 문장은 쓰지 않는다.";
        if (check.equals(TaskPipelineService.FLAW_POINTER_CHECK)) return "설명은 상황과 '추가:' 기능, 정할 것만 쓴다. 기존 계산·동작·결과를 기록·대장과 대조·검토·점검하라는 문장은 배경으로도 쓰지 않고, api 밖 클래스 이름은 쓰지 않는다.";
        if (check.equals(TaskPipelineService.GEN_META_CHECK)) return "파일 수, 글자 수, 맥락·첨부 한도, 필요한 자료 같은 과제 생성 정보는 쓰지 않는다.";
        if (check.equals(TaskPipelineService.DUPLICATE_CHECK)) return "같은 문장은 한 번만 쓴다. 각 사실은 가장 자연스러운 한 곳에 둔다.";
        if (check.equals(TaskPipelineService.HAN_CHECK)) return "한자(漢字)와 중국어 글자 없이 한글로만 쓴다.";
        if (check.equals(TaskPipelineService.FACTS_CHECK)) return "사실 목록의 문장과 예를 하나도 빼지 말고 그대로 넣는다.";
        if (check.equals(BizNumbersService.NAME)) return "문서에는 사실 문장과 CSV 의 숫자만 쓴다.";
        if (check.equals(TaskPipelineService.LEAK_A_GATE) || check.equals(TaskPipelineService.LEAK_B_GATE) || check.equals(TaskPipelineService.CHOICE_CHECK)
                || check.equals(BizQuestionService.CHECK)) {
            return "원인, 결함 위치, 계산 방법·순서, 규칙 문장, 다른 문항의 답은 쓰지 않는다. 받은 사실·예·구할 값·보기 후보만 쓰고 배경은 숫자 없이 채운다.";
        }
        return "이 확인을 통과하도록 쓴다.";
    }

    private static final int MIN_SENTENCE_CHARS = 12;
    private static final String BS_R = "\\R";
    private static final java.util.regex.Pattern HAN = java.util.regex.Pattern.compile("\\p{IsHan}+");

    /** 같은 문장(띄어쓰기·문장부호 무시)이 자료에 두 번 이상 나오면 실패: 사실은 가장 자연스러운 한 곳에 한 번만 둔다. 짧은 줄(제목 등)은 세지 않는다 */
    static GenCheckResultDto duplicateCheck(List<String> pages, String briefText, BlueprintDto bp) {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        for (String page : pages) { // 문서끼리는 모든 문장을 센다. 쪽 제목(첫 줄)은 세지 않는다
            String[] lines = page.split(BS_R, 2);
            String body = lines.length > 1 ? lines[1] : "";
            for (String s : sentences(body)) {
                String n = GenTextService.norm(s);
                if (n.length() >= MIN_SENTENCE_CHARS) seen.computeIfAbsent(n, k -> new ArrayList<>()).add(s);
            }
        }
        // 설명·요구사항과 문서 사이는 설계도의 사실 문장일 때만 센다(같은 안내 문구가 오가는 것은 허용)
        java.util.Set<String> facts = new java.util.HashSet<>();
        if (bp != null) bp.facts().forEach(f -> facts.add(GenTextService.norm(f.text())));
        for (String s : sentences(briefText)) {
            String n = GenTextService.norm(s);
            if (n.length() >= MIN_SENTENCE_CHARS && facts.contains(n)) seen.computeIfAbsent(n, k -> new ArrayList<>()).add(s);
        }
        List<String> dup = seen.values().stream().filter(l -> l.size() > 1).map(l -> l.size() + "번: " + l.get(0)).toList();
        if (dup.isEmpty()) return new GenCheckResultDto(TaskPipelineService.DUPLICATE_CHECK, true, "같은 문장이 되풀이되지 않습니다.");
        return new GenCheckResultDto(TaskPipelineService.DUPLICATE_CHECK, false, "같은 문장이 자료에 여러 번 나옵니다(한 번만 두세요): "
                + String.join(" / ", dup.subList(0, Math.min(5, dup.size()))));
    }

    /** 한자 검사 글 표시 → 그 글을 만든 단계 */
    private static final Map<String, String> HAN_TAG_STAGE = Map.of("[설계도]", "BLUEPRINT", "[작성]", "WRITING", "[코드]", "CODE",
            "[문항]", "QUESTIONS", "[정답]", "ANSWER", "[관련 적은 자료]", "FILLER");

    /** 한국어 글에 한자(중국어 글자)가 섞였는지. parts 는 [단계 표시] → 그 단계가 만든 글. 상세 앞의 표시가 다시 돌릴 단계를 가른다 */
    static GenCheckResultDto hanCheck(Map<String, String> parts) {
        List<String> bad = new ArrayList<>();
        java.util.Set<String> stages = new java.util.LinkedHashSet<>();
        parts.forEach((tag, text) -> {
            if (text == null) return;
            var m = HAN.matcher(text);
            List<String> hits = new ArrayList<>();
            while (m.find() && hits.size() < 3) hits.add(text.substring(Math.max(0, m.start() - 8), Math.min(text.length(), m.end() + 8)).replaceAll("\\s+", " "));
            if (!hits.isEmpty()) {
                bad.add(tag + " " + hits);
                String stage = HAN_TAG_STAGE.get(tag);
                if (stage != null) stages.add(stage);
            }
        });
        if (bad.isEmpty()) return new GenCheckResultDto(TaskPipelineService.HAN_CHECK, true, "한자가 섞여 있지 않습니다.");
        return new GenCheckResultDto(TaskPipelineService.HAN_CHECK, false, "한국어 글에 한자(중국어 글자)가 있습니다. 한글로만 쓰세요: " + String.join(" / ", bad))
                .withStages(stages.toArray(String[]::new));
    }

    private static final List<String> META_WORDS = List.of("응시자", "AI 어시스턴트", "이 페이지는", "이 문서는 과제", "과제 설계");

    /** BIZ 문서 쪽 글에 과제 설계자의 말(응시자·AI·기획안 양식 언급)이 있으면 실패. 응시자 지시(description·requirements)는 대상이 아니다 */
    static GenCheckResultDto metaCheck(String documentText) {
        List<String> bad = new ArrayList<>();
        for (String s : sentences(documentText)) {
            String flat = s.replaceAll("\\s+", " ");
            if (META_WORDS.stream().anyMatch(flat::contains)) bad.add(s);
        }
        if (bad.isEmpty()) return new GenCheckResultDto(TaskPipelineService.META_CHECK, true, "자료에 과제 설계자의 말이 없습니다.");
        return new GenCheckResultDto(TaskPipelineService.META_CHECK, false, "문서가 실제 업무 문서가 아니라 과제 설명처럼 쓰였습니다(응시자·AI·기획안 언급 문장): "
                + String.join(" / ", bad.subList(0, Math.min(5, bad.size()))));
    }

    /** 생성기 내부 정보 표현(기준 3장 명세 경계): 글자 수, 맥락·첨부 한도, 필요한 묶음, 관련 적은·헷갈리는 자료, 자료·파일 개수 */
    private static final Pattern GEN_META = Pattern.compile(
            "\\d[\\d,]*\\s*자(?=\\s|$|[.,)를이가의로와]|\\s*(이상|이하|안팎|내외|분량))|글자\\s*수|맥락\\s*한도|첨부\\s*(한도|제한)|필요한\\s*묶음|관련\\s*적은\\s*자료|헷갈리는\\s*자료"
                    + "|(자료|파일|문서)\\s*\\d+\\s*개|\\d+\\s*개(의)?\\s*(자료|파일|문서)");

    /**
     * 응시자에게 보이는 글(설명·요구사항·NOTES·문서)에 생성기 내부 정보가 있으면 실패(2026-11-26 g6: NOTES 에 파일 수·글자 수가 샘). 처음엔 경고만
     */
    static GenCheckResultDto genMetaCheck(String visibleText) {
        List<String> bad = new ArrayList<>();
        for (String s : sentences(visibleText == null ? "" : visibleText)) {
            if (GEN_META.matcher(s).find()) bad.add(s);
        }
        if (bad.isEmpty()) return new GenCheckResultDto(TaskPipelineService.GEN_META_CHECK, true, "응시자 글에 파일 수·글자 수·맥락 한도 같은 생성기 말이 없습니다.", GenCheckResultDto.WARN);
        return new GenCheckResultDto(TaskPipelineService.GEN_META_CHECK, false, "응시자 글에 생성기 내부 정보(파일 수·글자 수·맥락 한도·필요한 묶음)가 있습니다: "
                + String.join(" / ", bad.subList(0, Math.min(5, bad.size()))), GenCheckResultDto.WARN);
    }

    private static final Pattern REVIEW_VERB = Pattern.compile("검토|대조|확인|점검|살펴|검증|비교");
    /** ④ g9 판정: "기존 마감일 계산을 확정 대장과 대조", "기존 조회 결과 점검", "지급 내역을 대조하는 작업" (g9-dev 10개 중 6개, 오탐 0) */
    private static final Pattern EXISTING_NOUN = Pattern.compile("기존[^.。\n]{0,40}?(계산|동작|결과|합계|처리|로직|구현|코드|기능|조회|판정|지급액|금액|산정|집계|마감일|발급|규칙)");
    private static final Pattern CHECK_VERB = Pattern.compile("(대조|검토|확인|점검|검증|비교|살펴)(?!하는 기능|할 수 있는 기능|하는 조회)");
    /** ④ 기존 동작을 기록과 대조·검토하라는 문장인가(작성 결과 검사와 설계도 형식 검사가 같이 쓴다) */
    static boolean comparesExisting(String s) {
        return s != null && !s.contains("→") && ((EXISTING_NOUN.matcher(s).find() && CHECK_VERB.matcher(s).find()) || RECORD_WORK.matcher(s).find());
    }

    private static final Pattern RECORD_WORK = Pattern.compile("(기록|대장|내역|정산|로그|장부)[^.。\n]{0,20}?(대조|검증)|(대조|검증)하는 작업|(대조|검증)(와|과|하고) ");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,})\"");

    /**
     * 설명·요구사항이 결함 위치를 지목하는가(DEV, 경고만, 2026-11-26 g7 판정 01·10). 결정적으로 세 가지를 본다:
     * ① 결함 클래스 이름(flaws.where), ② 기존 메서드를 검토·대조 대상으로 지목하는 설명 문장("기존 X.f 의 결과를 기록과 대조하세요"),
     * ③ 결함 사례(caughtBy·separatedBy)의 따옴표 입력값이 설명에 그대로 있음. 요구사항의 예 줄("→" 가 있는 줄)은 ②에서 뺀다
     */
    static GenCheckResultDto flawPointerCheck(BlueprintDto bp, List<String> description, List<String> requirements) {
        List<String> hits = new ArrayList<>();
        String all = String.join("\n", description) + "\n" + String.join("\n", requirements);
        for (String cls : DevBlueprintService.flawClasses(bp)) {
            if (Pattern.compile("(?<![\\w])" + Pattern.quote(cls) + "(?![\\w])").matcher(all).find()) hits.add("결함 클래스 " + cls + " 이름");
        }
        List<String> existing = new ArrayList<>();
        for (String a : bp.api() == null ? List.<String>of() : bp.api()) {
            if (a == null || a.strip().startsWith("추가:")) continue; // "기존:" 이거나 표시가 없는 것(옛 설계도)은 이미 있는 메서드
            var m = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(").matcher(a);
            if (m.find()) existing.add(m.group(1));
        }
        for (String s : sentences(String.join("\n", description))) {
            if (s.contains("→")) continue;
            for (String name : existing) {
                if (Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])").matcher(s).find() && REVIEW_VERB.matcher(s).find()) {
                    hits.add("기존 메서드 " + name + " 를 검토 대상으로 지목: " + s);
                    break;
                }
            }
        }
        for (String s : sentences(String.join("\n", description))) {
            if (s.contains("→")) continue;
            if (comparesExisting(s)) hits.add("기존 동작을 기록과 대조·검토하라는 문장: " + s.strip());
        }
        boolean strong = !hits.isEmpty(); // ①②④ 는 실패, 아래 ③ 만 있으면 경고
        java.util.Set<String> flawCases = new java.util.HashSet<>();
        for (var f : bp.flaws() == null ? List.<BlueprintDto.Flaw>of() : bp.flaws()) {
            if (f.caughtBy() != null) flawCases.add(f.caughtBy().strip());
            if (f.separatedBy() != null) flawCases.add(f.separatedBy().strip());
        }
        // ③ 결함 사례 입력값: 예(요구사항·설명의 "→" 줄, 설계도 examples)에 이미 나온 값은 뺀다(g8 경고 5개가 모두 예와 같은 이름이라 오탐)
        StringBuilder exampleText = new StringBuilder();
        for (String line : requirements) exampleText.append(line).append('\n');
        for (String line : description) if (line.contains("→")) exampleText.append(line).append('\n');
        for (var e : bp.examples() == null ? List.<BlueprintDto.Example>of() : bp.examples()) exampleText.append(e.input()).append(' ').append(e.expected()).append('\n');
        StringBuilder descText = new StringBuilder();
        for (String line : description) if (!line.contains("→")) descText.append(line).append('\n');
        String desc = descText.toString();
        for (var c : bp.cases() == null ? List.<BlueprintDto.Case>of() : bp.cases()) {
            if (c.name() == null || !flawCases.contains(c.name().strip()) || c.situation() == null) continue;
            var m = QUOTED.matcher(c.situation());
            while (m.find()) {
                String v = m.group(1);
                if (desc.contains(v) && !exampleText.toString().contains(v)) hits.add("결함 사례 " + c.name() + " 의 입력 \"" + v + "\"");
            }
        }
        if (hits.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FLAW_POINTER_CHECK, true, "설명·요구사항이 결함 클래스·검토할 기존 메서드·결함 사례 입력을 지목하지 않습니다.", GenCheckResultDto.WARN);
        String detail = "설명·요구사항이 결함 위치를 지목합니다: " + String.join(" / ", hits.stream().distinct().limit(5).toList());
        return strong ? new GenCheckResultDto(TaskPipelineService.FLAW_POINTER_CHECK, false, detail) // g10: 실패로 바꿈(작성 단계 다시)
                : new GenCheckResultDto(TaskPipelineService.FLAW_POINTER_CHECK, false, detail, GenCheckResultDto.WARN);
    }

    /**
     * BIZ: 요청 글(설명·요구사항)과 문항이 필요한 자료를 지목하는가(경고만, 2026-11-26 g8 판정 07: "시설별 전력 사용량 자료와 계량기 안내서를 대조하고").
     * 필요한 파일(neededFiles)의 이름(폴더·확장자 뺌)이 띄어쓰기를 빼고 그대로 있거나, 이름의 낱말(2글자 이상, 2개 이상)이 한 문장에 모두 있으면 지목으로 본다.
     * g9: 짧은 이름(띄어쓰기 뺀 7자 미만, 예: '문의 이력', '생산 마감')이나 낱말 일치는 같은 문장에 지목 동사(대조·확인·참고·비교·살펴·보고)가 있을 때만 건다
     */
    private static final Pattern POINTING_VERB = Pattern.compile("대조|확인|참고|비교|살펴|보고(?!서)|검토");
    /** 이 길이(띄어쓰기 뺀 글자 수)보다 짧은 자료 이름은 지목 동사가 있을 때만 지목으로 본다 */
    private static final int SHORT_NAME = 7;

    static GenCheckResultDto bizMaterialPointerCheck(BlueprintDto bp, List<String> description, List<String> requirements, List<String> questions) {
        List<String> hits = new ArrayList<>();
        java.util.Map<String, List<String>> parts = new java.util.LinkedHashMap<>();
        parts.put("요청 글", new ArrayList<>(description));
        parts.get("요청 글").addAll(requirements);
        parts.put("문항", questions);
        for (String path : bp.neededFiles() == null ? List.<String>of() : bp.neededFiles()) {
            String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[A-Za-z0-9]+$", "").strip();
            if (name.isEmpty()) continue;
            String flat = name.replaceAll("\\s+", "");
            List<String> words = java.util.Arrays.stream(name.split("[\\s_()\\-]+")).filter(w -> w.length() >= 2).toList();
            for (var e : parts.entrySet()) {
                for (String s : sentences(String.join("\n", e.getValue()))) {
                    String sf = s.replaceAll("\\s+", "");
                    boolean pointing = POINTING_VERB.matcher(s).find();
                    boolean exact = flat.length() >= 3 && sf.contains(flat) && (flat.length() >= SHORT_NAME || pointing);
                    boolean byWords = words.size() >= 2 && words.stream().allMatch(sf::contains) && pointing;
                    if (exact || byWords) {
                        hits.add("[" + e.getKey() + "] 필요한 자료 '" + name + "': " + s.strip());
                        break;
                    }
                }
            }
        }
        if (hits.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FLAW_POINTER_CHECK, true, "요청 글·문항이 필요한 자료 이름을 대지 않습니다.", GenCheckResultDto.WARN);
        List<String> shown = hits.stream().distinct().limit(5).toList();
        boolean inQuestions = shown.stream().anyMatch(h -> h.startsWith("[문항]")), inRequest = shown.stream().anyMatch(h -> h.startsWith("[요청 글]"));
        String[] stages = !inQuestions ? new String[] {"WRITING"} : inRequest ? new String[] {"WRITING", "QUESTIONS"} : new String[] {"QUESTIONS"};
        return new GenCheckResultDto(TaskPipelineService.FLAW_POINTER_CHECK, false, "요청 글·문항이 필요한 자료를 지목합니다: " + String.join(" / ", shown), GenCheckResultDto.WARN).withStages(stages);
    }

    private static final Pattern FILE_MENTION = Pattern.compile("[\\w가-힣.\\-]+\\.(?:java|md|pdf|csv|json|log|txt|xml|yml)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 설명·요구사항이 말한 파일 이름(*.java, *.md, *.pdf, *.csv 꼴)이 응시자에게 보이는 파일(경로 끝)에 있어야 한다.
     * 없는 파일을 보라고 하면 응시자가 헷갈린다. 확장자 없이 부른 이름("검토 요청서")은 코드로 알 수 없어 보지 않는다
     */
    static GenCheckResultDto mentionedFilesCheck(String instructionText, java.util.Collection<String> visiblePaths) {
        List<String> visible = visiblePaths.stream().map(p -> p.toLowerCase(java.util.Locale.ROOT)).toList();
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        var m = FILE_MENTION.matcher(instructionText == null ? "" : instructionText);
        while (m.find()) {
            String name = m.group().toLowerCase(java.util.Locale.ROOT);
            if (name.matches("\\d+\\.\\w+")) continue; // "1.5" 같은 숫자
            if (visible.stream().noneMatch(p -> p.equals(name) || p.endsWith("/" + name) || p.endsWith(name))) missing.add(m.group());
        }
        if (missing.isEmpty()) return new GenCheckResultDto(TaskPipelineService.MENTION_CHECK, true, "설명이 말한 파일이 모두 보이는 파일에 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.MENTION_CHECK, false, "설명·요구사항이 말한 파일이 응시자에게 보이는 파일에 없습니다: " + missing
                + " (보이는 파일: " + visiblePaths + "). 없는 파일을 가리키지 말고 실제 있는 자료 이름만 쓰세요.");
    }

    private static final Pattern FILLER = Pattern.compile("(펼쳤|펼쳐 (놓|두)|정돈했|정리해 (놓|두)|묶어 (두|놓)|옮겨 (적|놓)|옮겼|꺼내 (놓|두)|훑어보았|넘겨 보았|올려 (놓|두))[가-힣 ]{0,6}[.]?$");

    private static final Pattern PERSON_SUBJECT = Pattern.compile("(담당자|팀장|[가-힣]{0,6}팀)(은|는|이|가|에서는)\\s");
    private static final Pattern STATUS_END = Pattern.compile("(중이다|중입니다|요청한다|요청했다|요청하였다|요청합니다|않다|않았다|않습니다|했다|하였다|했습니다|한다|합니다)[.]?$");
    private static final int REPEAT_LIMIT = 3;

    /** 담당자·팀이 주어이고 숫자 없이 동작·상태로만 끝나는 문장("담당자는 … 검토 중이다")이 3개 이상이면 그 문장들. 아니면 빈 목록 */
    static List<String> repeatedFiller(String documentText) {
        List<String> hits = new ArrayList<>();
        for (String s : sentences(documentText)) {
            String t = s.strip();
            if (!t.matches(".*\\d.*") && PERSON_SUBJECT.matcher(t).find() && STATUS_END.matcher(t).find()) hits.add(s);
        }
        return hits.size() >= REPEAT_LIMIT ? hits : List.of();
    }

    /**
     * 경고만: 사람의 동작만 서술하는 문장(서류를 펼쳤다, 메모에 옮겼다 …)이나, 사실·숫자 없이 "담당자는 … 중이다/요청한다" 꼴이 3개 이상 되풀이되면 알린다.
     * 반복은 BizPipelineService 가 작성 단계에 한 번 되돌려 보낸다
     */
    static GenCheckResultDto fillerCheck(String documentText) {
        List<String> hits = new ArrayList<>();
        for (String s : sentences(documentText)) if (FILLER.matcher(s.strip()).find()) hits.add(s);
        List<String> repeated = repeatedFiller(documentText);
        repeated.stream().filter(r -> !hits.contains(r)).forEach(hits::add);
        if (hits.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FILLER_CHECK, true, "동작만 서술하는 문장이 없습니다.");
        return new GenCheckResultDto(TaskPipelineService.FILLER_CHECK, false, "문서에 사람의 동작만 서술하거나 같은 꼴로 되풀이되는 문장이 있습니다(" + hits.size() + "개): "
                + String.join(" / ", hits.subList(0, Math.min(3, hits.size()))), GenCheckResultDto.WARN);
    }

    /** 반복 군더더기를 작성 단계에 되돌릴 때 붙이는 안내(결함 정보 없이 이전 글의 문장만 인용) */
    static String fillerFeedback(List<String> repeated) {
        return "앞서 쓴 글에 담당자·팀이 한 일이나 상태만 말하는 문장이 되풀이됩니다. 그런 문장은 빼고, 모든 문장이 사실·숫자·판단·요청 중 하나를 담도록 같은 사실 목록으로 처음부터 다시 쓰세요. 고칠 문장:\n- "
                + String.join("\n- ", repeated.subList(0, Math.min(5, repeated.size())));
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text == null ? new String[0] : text.split("\\R")) {
            for (String s : line.split("(?<=[.!?。])\\s+")) if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }
}
