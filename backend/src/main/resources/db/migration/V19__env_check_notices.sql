-- 환경 점검 콘솔 확인: 점검 진행 중 상태(실시간)와 콘솔이 응시자에게 보내는 안내 메시지.
-- 진행 상태는 대상자당 1건을 계속 덮어쓴다 (동의 저장 뒤의 최종 결과는 proctor_consents.env_check 에 따로 있다).
CREATE TABLE env_check_progress (
    participant_id  BIGINT       PRIMARY KEY REFERENCES exam_participants (participant_id) ON DELETE CASCADE,
    result          JSONB        NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
COMMENT ON TABLE  env_check_progress IS '입장 대기 중 환경 점검 진행 상태(콘솔 실시간 확인용, 대상자당 최신 1건)';
COMMENT ON COLUMN env_check_progress.result IS '점검 항목별 상태 JSON (proctor_consents.env_check 와 같은 모양)';

CREATE TABLE proctor_notices (
    notice_id       BIGSERIAL    PRIMARY KEY,
    participant_id  BIGINT       NOT NULL REFERENCES exam_participants (participant_id) ON DELETE CASCADE,
    message         VARCHAR(500) NOT NULL,
    sent_by         BIGINT,
    sent_at         TIMESTAMPTZ  NOT NULL,
    read_at         TIMESTAMPTZ
);
CREATE INDEX idx_proctor_notices_participant ON proctor_notices (participant_id, notice_id);
COMMENT ON TABLE  proctor_notices IS '콘솔이 응시자에게 보낸 안내 메시지(일방향, 응시자 답장 없음)';
COMMENT ON COLUMN proctor_notices.sent_by IS '보낸 담당자 사용자 ID';
COMMENT ON COLUMN proctor_notices.read_at IS '응시자가 확인을 누른 시각, 안 읽었으면 비움';
