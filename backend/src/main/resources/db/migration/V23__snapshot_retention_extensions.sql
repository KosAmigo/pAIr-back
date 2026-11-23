-- 감독 사진 보관 연장 이력. 보관은 회차 종료 7일(설정), 삭제 예정 2일 전부터 7일씩 최대 2번 연장한다.
-- (session_id, seq) 유니크로 동시에 눌러도 최대 횟수를 넘지 않는다. 사진 자체는 파일 저장소에 있고 여기엔 이력만 둔다.
CREATE TABLE snapshot_retention_extensions (
    extension_id   BIGSERIAL PRIMARY KEY,
    session_id     BIGINT      NOT NULL REFERENCES exam_sessions (session_id),
    seq            INT         NOT NULL,
    extended_by    BIGINT      NOT NULL REFERENCES users (user_id),
    extended_at    TIMESTAMPTZ NOT NULL,
    days           INT         NOT NULL,
    expires_before TIMESTAMPTZ NOT NULL,
    expires_after  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_retention_ext_seq UNIQUE (session_id, seq)
);
CREATE INDEX idx_retention_ext_session ON snapshot_retention_extensions (session_id);
