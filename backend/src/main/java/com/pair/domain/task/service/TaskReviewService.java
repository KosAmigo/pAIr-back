package com.pair.domain.task.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.task.entity.QuestionChoiceRecord;
import com.pair.domain.task.repository.QuestionChoiceRecordRepository;
import com.pair.domain.task.entity.QuestionSourceRecord;
import com.pair.domain.task.repository.QuestionSourceRecordRepository;
import com.pair.domain.task.entity.ShortAnswerKeyRecord;
import com.pair.domain.task.repository.ShortAnswerKeyRecordRepository;
import com.pair.domain.task.entity.TaskFileRecord;
import com.pair.domain.task.repository.TaskFileRecordRepository;
import com.pair.domain.task.entity.TaskQuestionRecord;
import com.pair.domain.task.repository.TaskQuestionRecordRepository;

/**
 * 관리자 검수용: 과제의 모든 파일(숨은 파일 포함)과 문항(정답 포함).
 * 응시자 API 와 달리 정답·숨은 파일을 그대로 주므로 컨트롤러에서 ADMIN 만 부른다.
 * 실전 복사본은 원본 과제의 내용을 쓴다.
 */
@Service
public class TaskReviewService {

    public record FileView(String path, String visibility, boolean readOnly, String content) {}

    public record ChoiceView(long id, int seq, String content, boolean correct) {}

    public record SourceView(String path, String pages) {}

    public record QuestionView(long id, int seq, String type, String content, Integer points,
            List<ChoiceView> choices, List<String> answers, List<SourceView> sources) {}

    private final TaskCatalog catalog;
    private final TaskFileRecordRepository files;
    private final TaskQuestionRecordRepository questions;
    private final QuestionChoiceRecordRepository choices;
    private final ShortAnswerKeyRecordRepository keys;
    private final QuestionSourceRecordRepository sources;

    public TaskReviewService(TaskCatalog catalog, TaskFileRecordRepository files, TaskQuestionRecordRepository questions,
            QuestionChoiceRecordRepository choices, ShortAnswerKeyRecordRepository keys, QuestionSourceRecordRepository sources) {
        this.catalog = catalog;
        this.files = files;
        this.questions = questions;
        this.choices = choices;
        this.keys = keys;
        this.sources = sources;
    }

    private long contentTaskId(String code) {
        return catalog.require(catalog.definitionCode(catalog.require(code))).getId();
    }

    /** 보이는 파일 먼저, 그다음 숨은 파일. 각각 경로 순. PDF 처럼 content 가 없으면 null */
    @Transactional(readOnly = true)
    public List<FileView> files(String code) {
        long taskId = contentTaskId(code);
        List<FileView> out = new ArrayList<>();
        for (String visibility : new String[] {TaskFileRecord.VISIBLE, TaskFileRecord.HIDDEN}) {
            for (TaskFileRecord f : files.findByTaskIdAndVisibilityOrderByPathAsc(taskId, visibility)) {
                out.add(new FileView(f.getPath(), f.getVisibility(), f.isReadOnly(), f.getContent()));
            }
        }
        return out;
    }

    /** 과제 문항(TASK 단계, 사용 중인 것만) seq 순. 후속 질문은 넣지 않는다 */
    @Transactional(readOnly = true)
    public List<QuestionView> questions(String code) {
        long taskId = contentTaskId(code);
        List<TaskQuestionRecord> qs = questions.findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(taskId, "TASK");
        if (qs.isEmpty()) return List.of();
        List<Long> ids = qs.stream().map(TaskQuestionRecord::getId).toList();

        Map<Long, List<ChoiceView>> choiceMap = new HashMap<>();
        for (QuestionChoiceRecord c : choices.findByQuestionIdInOrderBySeqAsc(ids)) {
            choiceMap.computeIfAbsent(c.getQuestionId(), k -> new ArrayList<>())
                    .add(new ChoiceView(c.getId(), c.getSeq(), c.getContent(), c.isCorrect()));
        }
        Map<Long, List<String>> answerMap = new HashMap<>();
        for (ShortAnswerKeyRecord k : keys.findByQuestionIdInOrderByIdAsc(ids)) {
            answerMap.computeIfAbsent(k.getQuestionId(), x -> new ArrayList<>()).add(k.getAccepted());
        }
        Map<Long, String> pathById = new HashMap<>();
        files.findAllById(sources.findByQuestionIdIn(ids).stream().map(QuestionSourceRecord::getTaskFileId).distinct().toList())
                .forEach(f -> pathById.put(f.getId(), f.getPath()));
        Map<Long, List<SourceView>> sourceMap = new HashMap<>();
        for (QuestionSourceRecord s : sources.findByQuestionIdIn(ids)) {
            String path = pathById.get(s.getTaskFileId());
            if (path == null) continue;
            sourceMap.computeIfAbsent(s.getQuestionId(), x -> new ArrayList<>()).add(new SourceView(path, s.getPages()));
        }
        sourceMap.values().forEach(l -> l.sort(Comparator.comparing(SourceView::path)));

        return qs.stream().map(q -> new QuestionView(q.getId(), q.getSeq(), q.getQuestionType(), q.getContent(), q.getPoints(),
                choiceMap.getOrDefault(q.getId(), List.of()), answerMap.getOrDefault(q.getId(), List.of()),
                sourceMap.getOrDefault(q.getId(), List.of()))).toList();
    }
}
