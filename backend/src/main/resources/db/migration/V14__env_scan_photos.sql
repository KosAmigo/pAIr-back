-- 환경 점검 사진: 휴대폰으로 방을 한 바퀴(360도) 돌며 방향별로 찍은 사진과 책상 위 사진.
-- 감독 이벤트(proctor_events)와 따로 둔다: 시험 중 경고가 아니라 시험 전 점검 기록이라 이벤트 목록·통계에 섞이지 않는다.
-- 파일은 감독 사진과 같은 저장소(S3/로컬)에 올리고, 같은 보관 기간(회차 종료 30일)이 지나면 파일만 지운다.
CREATE TABLE env_scan_photos (
    env_scan_photo_id  BIGSERIAL    PRIMARY KEY,
    participant_id     BIGINT       NOT NULL REFERENCES exam_participants (participant_id) ON DELETE CASCADE,
    stage              VARCHAR(10)  NOT NULL CHECK (stage IN ('ROOM', 'DESK')),
    angle_deg          SMALLINT     CHECK (angle_deg BETWEEN 0 AND 359),
    objects            VARCHAR(400),
    phone_suspect      BOOLEAN      NOT NULL DEFAULT FALSE,
    storage_key        VARCHAR(255),
    size_bytes         INT          NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    captured_at        TIMESTAMPTZ  NOT NULL,
    deleted_at         TIMESTAMPTZ
);
CREATE INDEX idx_env_scan_photos_participant ON env_scan_photos (participant_id, env_scan_photo_id);
COMMENT ON TABLE  env_scan_photos IS '환경 점검 사진(방 360도, 책상 위)';
COMMENT ON COLUMN env_scan_photos.stage IS '단계(ROOM 방 360도, DESK 책상 위)';
COMMENT ON COLUMN env_scan_photos.angle_deg IS '방 스캔에서 이 사진을 찍은 방향(0~359도, 책상은 비움)';
COMMENT ON COLUMN env_scan_photos.objects IS '사진에서 감지한 물체 이름(쉼표로 구분)';
COMMENT ON COLUMN env_scan_photos.phone_suspect IS '휴대폰으로 감지된 장면인지(감독관이 먼저 확인할 수 있게 표시)';
COMMENT ON COLUMN env_scan_photos.storage_key IS '파일 저장소 키(보관 기간 뒤에도 남겨 두고 deleted_at 으로 지웠음을 표시)';
COMMENT ON COLUMN env_scan_photos.deleted_at IS '보관 기간이 지나 파일을 지운 시각';
