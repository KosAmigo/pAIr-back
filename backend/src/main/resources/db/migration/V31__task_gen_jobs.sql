-- AI 과제 생성 작업 상태를 DB 에 둔다(설계 pAIr-docs 설계/AI_과제_생성_설계.md 7장).
-- 서버를 다시 켜도 이어 가고, 여러 서버가 같은 DB 를 쓰므로 env(환경 이름)로 서버별 작업을 나눈다.
-- H2(테스트)와 PostgreSQL 에서 모두 되는 문법만 쓴다.
CREATE TABLE task_gen_jobs (
    id            VARCHAR(36)      PRIMARY KEY,
    batch_id      VARCHAR(36),
    env           VARCHAR(100)     NOT NULL,
    status        VARCHAR(12)      NOT NULL,
    step          VARCHAR(100),
    mode          VARCHAR(10)      NOT NULL,
    deadline_at   TIMESTAMPTZ,
    form          TEXT             NOT NULL,
    admin_id      BIGINT           NOT NULL,
    task_code     VARCHAR(50),
    passed        BOOLEAN,
    checks        TEXT,
    error         TEXT,
    cost_usd      DOUBLE PRECISION,
    worker_id     VARCHAR(36),
    heartbeat_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ      NOT NULL,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_task_gen_jobs_env_status ON task_gen_jobs (env, status, created_at);
CREATE INDEX idx_task_gen_jobs_batch ON task_gen_jobs (batch_id);
COMMENT ON TABLE  task_gen_jobs IS 'AI 과제 생성 작업(대기열). 서버를 다시 켜도 이어 간다';
COMMENT ON COLUMN task_gen_jobs.env IS '환경 이름(pair.env-name). 각 서버는 자기 env 작업만 줍는다';
COMMENT ON COLUMN task_gen_jobs.status IS 'QUEUED, RUNNING, DONE, FAILED, CANCELED';
COMMENT ON COLUMN task_gen_jobs.mode IS 'NOW(바로) 또는 BATCH(배치)';
COMMENT ON COLUMN task_gen_jobs.form IS '생성 폼(TaskGenRequest) json';
COMMENT ON COLUMN task_gen_jobs.checks IS '확인 결과 목록 json';
COMMENT ON COLUMN task_gen_jobs.heartbeat_at IS '돌고 있는 서버가 30초마다 갱신. 2분 넘게 끊기면 서버가 꺼진 것으로 보고 다시 줍는다';

-- 작업이 부른 모델 호출의 결과. 같은 입력이면 저장된 결과를 써서 이어 간다(input_hash + nth)
CREATE TABLE task_gen_calls (
    id                  VARCHAR(36)  PRIMARY KEY,
    job_id              VARCHAR(36)  NOT NULL,
    input_hash          VARCHAR(64)  NOT NULL,
    nth                 INT          NOT NULL,
    stage               VARCHAR(40)  NOT NULL,
    label               VARCHAR(40),
    via                 VARCHAR(10)  NOT NULL,
    vendor              VARCHAR(20),
    provider_batch_id   VARCHAR(100),
    status              VARCHAR(10)  NOT NULL,
    result              TEXT,
    input_tokens        BIGINT       NOT NULL,
    output_tokens       BIGINT       NOT NULL,
    cached_input_tokens BIGINT       NOT NULL,
    waited_ms           BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_task_gen_call UNIQUE (job_id, input_hash, nth)
);
COMMENT ON TABLE  task_gen_calls IS '생성 작업의 모델 호출 결과(이어 가기용)';
COMMENT ON COLUMN task_gen_calls.nth IS '같은 작업·같은 입력 해시의 몇 번째 호출인지(0부터)';
COMMENT ON COLUMN task_gen_calls.via IS 'DIRECT(바로) 또는 BATCH';
COMMENT ON COLUMN task_gen_calls.status IS 'PENDING 또는 DONE';
