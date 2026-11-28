CREATE TABLE task_issue_reports (
    issue_id       BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES tasks (task_id),
    user_id        BIGINT       NOT NULL REFERENCES users (user_id),
    category       VARCHAR(32)  NOT NULL CHECK (category IN ('QUESTION_ERROR', 'ANSWER_EXPLANATION', 'DESCRIPTION_GAP', 'DIFFICULTY', 'OTHER')),
    question_number VARCHAR(50),
    message        TEXT         NOT NULL CHECK (length(trim(message)) BETWEEN 5 AND 2000),
    status         VARCHAR(20)  NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'IN_REVIEW', 'RESOLVED', 'CLOSED')),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_task_issue_reports_created ON task_issue_reports (created_at DESC, issue_id DESC);
CREATE INDEX idx_task_issue_reports_task ON task_issue_reports (task_id, created_at DESC);
CREATE INDEX idx_task_issue_reports_status ON task_issue_reports (status, created_at DESC);

COMMENT ON TABLE task_issue_reports IS '응시자가 과제별로 제출한 문제 건의';
COMMENT ON COLUMN task_issue_reports.user_id IS '건의 제출자';
