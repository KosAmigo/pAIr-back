-- 이벤트 증거 구간 사진: 이벤트 앞 8초·뒤 4초 중 한 장 (PC 웹캠 또는 휴대폰 JPEG 프레임).
-- 사진 파일은 파일 저장소(exam-clips/...)에 있고 여기엔 키·크기·시점만 둔다. 보관·삭제는 감독 사진과 같은 규칙(종료 7일, 연장 반영).
CREATE TABLE proctor_event_frames (
    frame_id    BIGSERIAL PRIMARY KEY,
    session_id  BIGINT       NOT NULL REFERENCES exam_sessions (session_id),
    event_id    BIGINT       NOT NULL REFERENCES proctor_events (proctor_event_id),
    source      VARCHAR(12)  NOT NULL,
    offset_ms   INT          NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    size_bytes  INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_event_frame UNIQUE (event_id, source, offset_ms)
);
CREATE INDEX idx_event_frames_session ON proctor_event_frames (session_id) WHERE deleted_at IS NULL;
