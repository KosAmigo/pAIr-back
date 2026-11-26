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

    private static String lines(List<String> l) {
        StringBuilder sb = new StringBuilder();
        l.forEach(s -> sb.append("- ").append(s).append('\n'));
        return sb.toString();
    }

    /** 사실 목록의 문장과 예의 입력·기대 출력이 글에 그대로 들어갔는지(M4). examplesText 는 요구사항(DEV)에서 찾는다 */
    static GenCheckResultDto factsCheck(BlueprintDto b, String corpus, String examplesText) {
        String all = GenTextService.norm(corpus);
        String ex = GenTextService.norm(examplesText);
        List<String> missing = new ArrayList<>();
        for (var f : b.facts()) if (!all.contains(GenTextService.norm(f.text()))) missing.add(f.text());
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
        String all = GenTextService.norm(visibleText);
        List<String> missing = new ArrayList<>();
        for (var c : b.cases()) {
            if (c.basis() == null || c.basis().isBlank()) missing.add(c.name() + ": 근거 문장(basis)이 없음");
            else if (!all.contains(GenTextService.norm(c.basis()))) missing.add(c.name() + ": " + c.basis());
        }
        if (missing.isEmpty()) return new GenCheckResultDto(TaskPipelineService.BASIS_CHECK, true, "사례 " + b.cases().size() + "개의 근거 문장이 모두 글에 그대로 있습니다.");
        return new GenCheckResultDto(TaskPipelineService.BASIS_CHECK, false, "사례의 근거 문장이 글에 없습니다: " + String.join(" / ", missing.subList(0, Math.min(5, missing.size()))));
    }

    /**
     * 작성 단계를 다시 돌릴 때 붙이는 안내. 검사 상세에는 결함·핵심어가 들어 있을 수 있어 그대로 주지 않는다:
     * 검사 이름에 맞는 일반 규칙과, 작성 단계가 이전에 쓴 글 중 상세에 인용된 문장만 준다.
     */
    static String writerFeedback(List<GenCheckResultDto> failed, String previousText) {
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

    private static String rule(String check) {
        if (check.equals(TaskPipelineService.BASIS_CHECK)) return "사실 목록의 문장을 하나도 빼지 말고 그대로 넣는다(사례의 근거 문장도 사실 목록에 있다).";
        if (check.equals(FeatureCheckService.NAME)) return "시작 코드에 이미 있는 메서드는 추가·구현하라고 쓰지 않는다. 공개 API 목록에서 '추가:' 로 적힌 것만 새로 만들라고 쓴다.";
        if (check.equals(DevCheckService.FOLLOW_UP)) return "후속 질문에는 이 과제 고유의 명사·용어·값을 쓰지 말고, 무엇을 어떻게 검증했는지 묻는 일반 틀로만 쓴다.";
        if (check.equals(TaskPipelineService.MENTION_CHECK)) return "보이는 파일에 없는 파일 이름은 쓰지 않는다. 있는 자료 이름만 쓴다.";
        if (check.equals(TaskPipelineService.META_CHECK)) return "문서는 실제 업무 문서(메모, 로그, 보고서)처럼 쓴다. 과제, 응시자, AI, 기획안 양식을 말하는 문장은 쓰지 않는다.";
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

    /** 한국어 글에 한자(중국어 글자)가 섞였는지. parts 는 [단계 표시] → 그 단계가 만든 글. 상세 앞의 표시가 다시 돌릴 단계를 가른다 */
    static GenCheckResultDto hanCheck(Map<String, String> parts) {
        List<String> bad = new ArrayList<>();
        parts.forEach((tag, text) -> {
            if (text == null) return;
            var m = HAN.matcher(text);
            List<String> hits = new ArrayList<>();
            while (m.find() && hits.size() < 3) hits.add(text.substring(Math.max(0, m.start() - 8), Math.min(text.length(), m.end() + 8)).replaceAll("\\s+", " "));
            if (!hits.isEmpty()) bad.add(tag + " " + hits);
        });
        if (bad.isEmpty()) return new GenCheckResultDto(TaskPipelineService.HAN_CHECK, true, "한자가 섞여 있지 않습니다.");
        return new GenCheckResultDto(TaskPipelineService.HAN_CHECK, false, "한국어 글에 한자(중국어 글자)가 있습니다. 한글로만 쓰세요: " + String.join(" / ", bad));
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

    /** 경고만: 사람의 동작만 서술하는 문장(서류를 펼쳤다, 메모에 옮겼다 …)이 문서에 있으면 알린다(사실·숫자·판단·요청이 없는 군더더기) */
    static GenCheckResultDto fillerCheck(String documentText) {
        List<String> hits = new ArrayList<>();
        for (String s : sentences(documentText)) if (FILLER.matcher(s.strip()).find()) hits.add(s);
        if (hits.isEmpty()) return new GenCheckResultDto(TaskPipelineService.FILLER_CHECK, true, "동작만 서술하는 문장이 없습니다.");
        return new GenCheckResultDto(TaskPipelineService.FILLER_CHECK, false, "문서에 사람의 동작만 서술하는 문장이 있습니다(" + hits.size() + "개): "
                + String.join(" / ", hits.subList(0, Math.min(3, hits.size()))), GenCheckResultDto.WARN);
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text == null ? new String[0] : text.split("\\R")) {
            for (String s : line.split("(?<=[.!?。])\\s+")) if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }
}
