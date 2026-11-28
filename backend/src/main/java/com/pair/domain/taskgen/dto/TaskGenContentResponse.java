package com.pair.domain.taskgen.dto;

import java.time.Instant;
import java.util.List;

/**
 * 만드는 중인 과제의 중간 결과 요약(GET /api/admin/task-gen/{jobId}/content). 단계별 가장 최근 결과만 보여 주고,
 * 코드·문서 본문은 보내지 않는다(경로와 글자 수만). 아직 없거나 읽을 수 없는 칸, 직군에 없는 칸은 null
 */
public record TaskGenContentResponse(String jobId, String status, String step, Instant updatedAt, Form form, Blueprint blueprint, Writing writing,
        Code code, Answer answer, List<Question> questions, Code fillers) {

    public record Form(String track, String topic, String trap, String usage, Integer minutes, String mode) {}

    public record Blueprint(String title, String summary, String scenario, String category, List<String> competencies, List<String> api,
            List<Example> examples, List<Flaw> flaws, List<Judgment> judgments, List<String> neededFiles, List<Filler> fillers, List<Doc> docs,
            List<String> questionPlan) {}

    public record Example(String input, String expected) {}

    public record Flaw(String what, String where) {}

    public record Judgment(String question, List<String> options) {}

    public record Filler(String path, String summary, boolean confusing) {}

    public record Doc(String path, String role) {}

    /** documents: DEV 는 비어 있다. BIZ 는 문서별 경로와 글자 수 */
    public record Writing(List<String> description, List<String> requirements, String notes, List<FileInfo> documents) {}

    public record FileInfo(String path, int chars) {}

    public record Code(List<FileInfo> files) {}

    /** DEV: 숨은 테스트 파일과 테스트 메서드 이름, 오답·다른 풀이 개수. BIZ: 정답 키가 있는 문항 번호(keys) */
    public record Answer(List<HiddenTest> hiddenTests, int wrongFixes, int altSolutions, List<String> keys) {}

    public record HiddenTest(String path, List<String> methods) {}

    public record Question(int seq, String type, String content) {}
}
