package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.TaskGenCostEstimateResponse;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

/** 만들기 전 예상 비용: 같은 조건으로 최근 끝난 작업의 실제 비용 중앙값, 기록이 모자라면 상한에서 어림(2026-11-27) */
@Service
public class TaskGenCostService {

    static final int MIN_SAMPLES = 3;
    static final int MAX_SAMPLES = 20;
    /** 기록이 없을 때 상한 대비 어림 비율(BIZ 배치 g17~g19: 상한 $0.8 에 $0.24~0.42) */
    static final double DEFAULT_NOW_RATIO = 0.7;
    static final double DEFAULT_BATCH_RATIO = 0.4;

    private final TaskGenJobRepository jobs;
    private final TaskGenProperties props;
    private final ObjectMapper json;

    public TaskGenCostService(TaskGenJobRepository jobs, TaskGenProperties props, ObjectMapper json) {
        this.jobs = jobs;
        this.props = props;
        this.json = json;
    }

    public TaskGenCostEstimateResponse estimate(String track, Integer minutes, String mode, Integer count) {
        String t = "BIZ".equalsIgnoreCase(track) ? "BIZ" : "DEV";
        if (track != null && !t.equalsIgnoreCase(track)) throw new IllegalArgumentException("track 은 DEV 또는 BIZ 예요.");
        int m = minutes == null ? 60 : minutes;
        if (m <= 0) throw new IllegalArgumentException("minutes 는 1 이상이어야 해요.");
        String md = mode == null || mode.isBlank() ? TaskGenJob.NOW : mode.toUpperCase(Locale.ROOT);
        if (!TaskGenJob.NOW.equals(md) && !TaskGenJob.BATCH.equals(md)) throw new IllegalArgumentException("mode 는 NOW 또는 BATCH 예요.");
        int n = count == null ? 1 : count;
        if (n < 1) throw new IllegalArgumentException("count 는 1 이상이어야 해요.");

        double cap = props.costCap("DEV".equals(t), m);
        List<Double> costs = recentCosts(t, m, md);
        boolean history = costs.size() >= MIN_SAMPLES;
        double per = history ? median(costs) : cap * (TaskGenJob.BATCH.equals(md) ? DEFAULT_BATCH_RATIO : DEFAULT_NOW_RATIO);
        return new TaskGenCostEstimateResponse(t, m, md, n, round2(per), round2(cap), round2(per * n), round2(cap * n),
                history ? costs.size() : 0, history ? "HISTORY" : "DEFAULT");
    }

    private List<Double> recentCosts(String track, int minutes, String mode) {
        List<Double> out = new ArrayList<>();
        for (TaskGenJob j : jobs.findTop200ByStatusAndCostUsdNotNullOrderByFinishedAtDesc(TaskGenJob.DONE)) {
            if (!mode.equals(j.getMode()) || j.getCostUsd() <= 0) continue;
            TaskGenRequest f = readForm(j.getForm());
            if (f == null || !track.equalsIgnoreCase(f.track()) || f.minutes() == null || f.minutes() != minutes) continue;
            out.add(j.getCostUsd());
            if (out.size() >= MAX_SAMPLES) break;
        }
        return out;
    }

    private TaskGenRequest readForm(String text) {
        try {
            return json.readValue(text, TaskGenRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    static double median(List<Double> values) {
        List<Double> s = values.stream().sorted().toList();
        int k = s.size();
        return k % 2 == 1 ? s.get(k / 2) : (s.get(k / 2 - 1) + s.get(k / 2)) / 2;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
