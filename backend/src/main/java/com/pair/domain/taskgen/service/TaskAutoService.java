package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.AutoTopicDto;
import com.pair.domain.taskgen.dto.TaskGenAutoRequest;
import com.pair.domain.taskgen.dto.TaskGenPreviewRequest;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 자동 생성(설계 3·7장): 주제 미리보기와, 고른(또는 서버가 고른) 주제마다 작업 하나씩을 한 묶음으로 대기열에 넣는다.
 * 함정 유형 배정은 TrapTypeService, 주제 받기는 TaskSuggestionService, 대기열은 TaskGenJobService 가 맡는다.
 */
@Component
public class TaskAutoService {

    static final int DEFAULT_COUNT = 10;

    private final TaskSuggestionService suggestions;
    private final TrapTypeService trapTypes;
    private final TaskGenJobService jobs;

    public TaskAutoService(TaskSuggestionService suggestions, TrapTypeService trapTypes, TaskGenJobService jobs) {
        this.suggestions = suggestions;
        this.trapTypes = trapTypes;
        this.jobs = jobs;
    }

    /** 주제 count 개를 미리 보여 준다. 잘못된 입력은 IllegalArgumentException(400) */
    public List<AutoTopicDto> preview(TaskGenPreviewRequest req, long adminId) {
        if (req == null) throw new IllegalArgumentException("요청 내용이 없습니다.");
        return topics(req.track(), req.minutes(), req.count(), req.exclude(), adminId);
    }

    /** 자동 생성 시작: topics 가 있으면 그대로, 없으면 미리보기와 같은 방법으로 골라 작업을 만든다 */
    public TaskGenJobService.Batch start(TaskGenAutoRequest req, long adminId) {
        if (req == null) throw new IllegalArgumentException("요청 내용이 없습니다.");
        String track = TaskGenRequest.cleanTrack(req.track());
        List<AutoTopicDto> topics = req.topics() == null || req.topics().isEmpty()
                ? topics(track, req.minutes(), req.count(), List.of(), adminId) : req.topics();
        if (topics.size() > TaskSuggestionService.MAX_MANY) throw new IllegalArgumentException("주제는 " + TaskSuggestionService.MAX_MANY + "개까지입니다.");
        List<TaskGenRequest> forms = new ArrayList<>();
        for (AutoTopicDto t : topics) forms.add(form(track, req.minutes(), req.usage(), t));
        return jobs.startAll(forms, adminId, req.mode(), req.batchDeadlineHours());
    }

    private List<AutoTopicDto> topics(String track, Integer minutes, Integer count, List<String> exclude, long adminId) {
        String t = TaskGenRequest.cleanTrack(track);
        int m = minutes == null ? 60 : minutes;
        if (!TaskGenRequest.MINUTES.contains(m)) throw new IllegalArgumentException("제한 시간(minutes)은 20, 40, 60 중 하나여야 합니다.");
        int n = count == null ? DEFAULT_COUNT : count;
        if (n < 1 || n > TaskSuggestionService.MAX_MANY) throw new IllegalArgumentException("count 는 1~" + TaskSuggestionService.MAX_MANY + " 이어야 합니다.");
        var assigned = trapTypes.assign(t, n);
        return suggestions.suggestMany(new TaskSuggestionService.Request(t, "", exclude), m, n, assigned, adminId);
    }

    /** 주제 하나 → 생성 폼. 함정 앞에 "[R4 적용 순서]"를 붙인다(TrapTypeService 가 나중에 이 표시로 유형별 기록을 센다) */
    static TaskGenRequest form(String track, Integer minutes, String usage, AutoTopicDto t) {
        if (t == null) throw new IllegalArgumentException("주제(topics)에 빈 항목이 있습니다.");
        String type = t.trapType() == null ? "" : t.trapType().strip();
        String trap = (type.isEmpty() ? "" : "[" + type + "] ") + (t.trap() == null ? "" : t.trap().strip());
        return new TaskGenRequest(track, t.situation(), trap, usage, minutes, t.competencies()).validated();
    }
}
