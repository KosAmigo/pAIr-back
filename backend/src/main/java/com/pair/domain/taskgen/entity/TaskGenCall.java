package com.pair.domain.taskgen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 생성 작업이 부른 모델 호출 한 건의 결과(task_gen_calls). 같은 (작업, 입력 해시, nth)면 저장된 결과를 써서 이어 간다 */
@Entity
@Table(name = "task_gen_calls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskGenCall {

    public static final String DIRECT = "DIRECT";
    public static final String BATCH = "BATCH";
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(nullable = false)
    private int nth;

    @Column(nullable = false, length = 40)
    private String stage;

    @Column(length = 40)
    private String label;

    @Column(nullable = false, length = 10)
    private String via;

    @Column(length = 20)
    private String vendor;

    @Column(name = "provider_batch_id", length = 100)
    private String providerBatchId;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cached_input_tokens", nullable = false)
    private long cachedInputTokens;

    @Column(name = "waited_ms", nullable = false)
    private long waitedMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 바로 부른 호출의 결과 */
    public TaskGenCall(String id, String jobId, String inputHash, int nth, String stage, String label, String vendor, String result,
            long inputTokens, long outputTokens, Instant now) {
        this.id = id;
        this.jobId = jobId;
        this.inputHash = inputHash;
        this.nth = nth;
        this.stage = stage;
        this.label = label;
        this.via = DIRECT;
        this.vendor = vendor;
        this.status = DONE;
        this.result = result;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = now;
    }

    /** 배치로 보낸 호출(결과는 아직). 배치 id 가 있어 서버를 다시 켜도 그 배치를 다시 확인한다 */
    public static TaskGenCall pendingBatch(String id, String jobId, String inputHash, int nth, String stage, String label, String vendor,
            String providerBatchId, Instant now) {
        TaskGenCall c = new TaskGenCall(id, jobId, inputHash, nth, stage, label, vendor, null, 0, 0, now);
        c.via = BATCH;
        c.status = PENDING;
        c.providerBatchId = providerBatchId;
        return c;
    }

    /** 결과를 받아 DONE 으로 바꾼다(배치로 받았거나, 배치에서 빼고 바로 불러 받았을 때) */
    public void done(String via, String vendor, String result, long inputTokens, long outputTokens, long cachedInputTokens, long waitedMs) {
        this.via = via;
        if (vendor != null) this.vendor = vendor;
        this.status = DONE;
        this.result = result;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.waitedMs = waitedMs;
    }
}
