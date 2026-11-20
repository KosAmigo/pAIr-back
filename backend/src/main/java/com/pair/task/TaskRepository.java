package com.pair.task;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DB(tasks, task_files, task_questions)에서 과제 내용을 읽는다.
 * 조회할 때마다 DB 를 읽으므로 나중에 새로 들어온 과제(AI 생성 등)도 재시작 없이 보인다.
 * 실전(LIVE) 복사본은 내용 행이 없어 여기서 찾지 못한다. TaskCatalog 가 원본 code 로 바꿔 부른다.
 */
@Component
public class TaskRepository {

    /** 직군별 안내 문구. 과제 내용이 아니라 평가 방식 안내라 코드에 둔다 */
    private static final List<String> NOTES_DEV = List.of(
            "AI 답변을 그대로 믿지 말고 직접 검증하는 과정도 평가에 포함됩니다.",
            "AI는 대화창에 붙여 넣은 내용만 볼 수 있습니다. 필요한 코드와 로그는 직접 전달하세요.",
            "외부 검색은 허용되지 않습니다.");
    private static final List<String> NOTES_BIZ = List.of(
            "AI 답변을 그대로 믿지 말고 직접 검증하는 과정도 평가에 포함됩니다.",
            "AI는 대화창에 붙여 넣은 내용만 볼 수 있습니다. 필요한 자료는 직접 전달하세요.",
            "외부 검색은 허용되지 않습니다.");

    private final TaskRecordRepository records;
    private final TaskFileRecordRepository files;
    private final TaskQuestionRecordRepository questions;
    private final DocumentPageRecordRepository pages;
    private final ObjectMapper json;

    public TaskRepository(TaskRecordRepository records, TaskFileRecordRepository files,
            TaskQuestionRecordRepository questions, DocumentPageRecordRepository pages, ObjectMapper json) {
        this.records = records;
        this.files = files;
        this.questions = questions;
        this.pages = pages;
        this.json = json;
    }

    /** 내용이 DB 에 있는 모든 과제 (원본만, 번호 순). 관리자 과제 콘솔 목록용 */
    @Transactional(readOnly = true)
    public List<TaskDef> all() {
        return records.findAllByOrderByIdAsc().stream().filter(TaskRepository::hasContent).map(this::toDef).toList();
    }

    /** 이 code 의 과제 내용이 DB 에 있는지 (실전 복사본은 없다) */
    @Transactional(readOnly = true)
    public boolean has(String id) {
        return records.findByCode(id).filter(TaskRepository::hasContent).isPresent();
    }

    @Transactional(readOnly = true)
    public TaskDef get(String id) {
        return toDef(find(id));
    }

    /** 응시자에게 보이는 파일들 (상대 경로 → 내용). */
    @Transactional(readOnly = true)
    public Map<String, String> visibleFiles(String id) {
        return readFiles(find(id), TaskFileRecord.VISIBLE);
    }

    /** 숨은 테스트, 체크리스트 (상대 경로 → 내용). API로 내보내지 않는다. */
    @Transactional(readOnly = true)
    public Map<String, String> hiddenFiles(String id) {
        return readFiles(find(id), TaskFileRecord.HIDDEN);
    }

    /**
     * 채점에 쓸 제공 자료: 과제 원본의 공개 파일 중 "자료/" 아래 파일 (경로 → 글, 경로 순).
     * 텍스트 파일(CSV, md)은 content 그대로, PDF(content 없음)는 document_pages 를 쪽 순서로 "--- N쪽 ---" 줄과 함께 이어 붙인다.
     * 응시 작업 파일이 아니라 과제 원본에서 읽으므로 응시자가 자료를 고쳐도 채점 근거는 그대로다.
     * 실전 복사본은 원본 code 로 불러야 한다(TaskCatalog.definitionCode).
     */
    @Transactional(readOnly = true)
    public Map<String, String> materials(String id) {
        Map<String, String> out = new TreeMap<>();
        for (TaskFileRecord f : files.findByTaskIdAndVisibilityOrderByPathAsc(find(id).getId(), TaskFileRecord.VISIBLE)) {
            if (!f.getPath().startsWith("자료/")) continue;
            if (f.getContent() != null) {
                out.put(f.getPath(), f.getContent());
            } else if (f.getStorageKey() != null) {
                StringBuilder sb = new StringBuilder();
                for (DocumentPageRecord p : pages.findByTaskFileIdOrderByPageNoAsc(f.getId())) {
                    sb.append("--- ").append(p.getPageNo()).append("쪽 ---\n").append(p.getText().strip()).append("\n");
                }
                if (!sb.isEmpty()) out.put(f.getPath(), sb.toString());
            }
        }
        return out;
    }

    private TaskRecord find(String id) {
        return records.findByCode(id).filter(TaskRepository::hasContent)
                .orElseThrow(() -> new NoSuchElementException("없는 과제입니다: " + id));
    }

    /** 복사본이 아니고 settings 까지 채워진 행만 내용이 있는 과제로 본다 */
    private static boolean hasContent(TaskRecord t) {
        return t.getParentTaskId() == null && t.getSettings() != null;
    }

    private TaskDef toDef(TaskRecord t) {
        Settings s = readSettings(t);
        List<String> followUps = questions
                .findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(t.getId(), TaskQuestionRecord.FOLLOWUP).stream()
                .map(TaskQuestionRecord::getContent).toList();
        boolean dev = "DEV".equalsIgnoreCase(t.getTrack());
        return new TaskDef(t.getCode(), t.getTrack().toLowerCase(), t.getTitle(), t.getTimeLimitMin(),
                lines(t.getDescription()), lines(t.getRequirements()), dev ? NOTES_DEV : NOTES_BIZ, followUps,
                s.openFile, s.d4Minutes == null ? List.of() : s.d4Minutes,
                s.requiredHeadings == null ? List.of() : s.requiredHeadings, s.editableFile);
    }

    private static List<String> lines(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("\\R")).filter(l -> !l.isBlank()).toList();
    }

    /** 텍스트 파일(content 가 있는 것)만 읽는다. storage_key 만 있는 바이너리(PDF)는 아직 지원하지 않아 건너뛴다. */
    private Map<String, String> readFiles(TaskRecord t, String visibility) {
        Map<String, String> out = new TreeMap<>();
        for (TaskFileRecord f : files.findByTaskIdAndVisibilityOrderByPathAsc(t.getId(), visibility)) {
            if (f.getContent() != null) out.put(f.getPath(), f.getContent());
        }
        return out;
    }

    private Settings readSettings(TaskRecord t) {
        try {
            return json.readValue(t.getSettings(), Settings.class);
        } catch (IOException e) {
            throw new IllegalStateException("과제 settings 를 읽지 못했습니다: " + t.getCode(), e);
        }
    }

    /** tasks.settings JSON 모양 */
    private record Settings(String openFile, String editableFile, List<String> requiredHeadings,
            List<Integer> d4Minutes) {}
}
