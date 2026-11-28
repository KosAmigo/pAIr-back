package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.TaskGenProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 만들기 전 예상 비용: 같은 직군·제한시간·방식의 최근 비용 중앙값, 모자라면 상한에서 어림 */
class TaskGenCostServiceTest {

    private final TaskGenJobRepository jobs = mock(TaskGenJobRepository.class);
    private final TaskGenProperties props = mock(TaskGenProperties.class);
    private final TaskGenCostService service = new TaskGenCostService(jobs, props, new ObjectMapper());

    @BeforeEach
    void caps() {
        when(props.costCap(false, 40)).thenReturn(0.8);
        when(props.costCap(true, 40)).thenReturn(0.6);
    }

    private static TaskGenJob job(String track, int minutes, String mode, double cost) {
        TaskGenJob j = mock(TaskGenJob.class);
        when(j.getMode()).thenReturn(mode);
        when(j.getCostUsd()).thenReturn(cost);
        when(j.getForm()).thenReturn("{\"track\":\"" + track + "\",\"minutes\":" + minutes + "}");
        return j;
    }

    @Test
    void 같은_조건_기록이_있으면_중앙값에_개수를_곱한다() {
        var recent = List.of(job("BIZ", 40, "BATCH", 0.24), job("BIZ", 40, "BATCH", 0.30), job("BIZ", 40, "BATCH", 0.42),
                job("BIZ", 40, "NOW", 0.90), job("BIZ", 60, "BATCH", 0.70), job("DEV", 40, "BATCH", 0.50));
        when(jobs.findTop200ByStatusAndCostUsdNotNullOrderByFinishedAtDesc(TaskGenJob.DONE)).thenReturn(recent);
        var e = service.estimate("BIZ", 40, "BATCH", 3);
        assertThat(e.basis()).isEqualTo("HISTORY");
        assertThat(e.sampleSize()).isEqualTo(3);
        assertThat(e.perTaskUsd()).isEqualTo(0.30);
        assertThat(e.totalUsd()).isEqualTo(0.90);
        assertThat(e.perTaskCapUsd()).isEqualTo(0.8);
        assertThat(e.totalCapUsd()).isEqualTo(2.4);
    }

    @Test
    void 기록이_모자라면_상한에서_어림한다() {
        var recent = List.of(job("DEV", 40, "NOW", 0.5));
        when(jobs.findTop200ByStatusAndCostUsdNotNullOrderByFinishedAtDesc(TaskGenJob.DONE)).thenReturn(recent);
        var e = service.estimate("DEV", 40, "NOW", 2);
        assertThat(e.basis()).isEqualTo("DEFAULT");
        assertThat(e.sampleSize()).isZero();
        assertThat(e.perTaskUsd()).isEqualTo(0.42); // 0.6 × 0.7
        assertThat(e.totalCapUsd()).isEqualTo(1.2);
    }

    @Test
    void 잘못된_값은_거절한다() {
        assertThatThrownBy(() -> service.estimate("PM", 40, "NOW", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.estimate("BIZ", 40, "LATER", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.estimate("BIZ", 40, "NOW", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
