-- 환경 점검 사진(귀 왼쪽·오른쪽, 손목): 점검 화면이 찍어 둔 사진을 감독관이 볼 수 있게 올려 둔다.
-- 이어폰·스마트워치는 자동으로 분석하지 않고 감독관이 사진으로 확인한다. 대상자·단계마다 한 장이고, 다시 찍으면 덮어쓴다.
-- 감독 이벤트(proctor_events)와 따로 둔다: 시험 중 경고가 아니라 시험 전 점검 기록이다.
-- 파일은 감독 사진과 같은 저장소(S3/로컬)에 올리고, 회차 종료 후 감독 사진과 같은 보관 기간이 지나면 파일만 지운다.
-- (이전의 env_scan_photos 는 책상 위·방 360도 사진용이었고 V27 에서 제거했다. 이 표는 그것과 별개다.)
CREATE TABLE env_check_photos (
    env_check_photo_id BIGSERIAL    PRIMARY KEY,
    participant_id     BIGINT       NOT NULL REFERENCES exam_participants (participant_id) ON DELETE CASCADE,
    stage              VARCHAR(12)  NOT NULL CHECK (stage IN ('EAR_LEFT', 'EAR_RIGHT', 'WRIST')),
    storage_key        VARCHAR(255),
    size_bytes         INT          NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    captured_at        TIMESTAMPTZ  NOT NULL,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_env_check_photo UNIQUE (participant_id, stage)
);
COMMENT ON TABLE  env_check_photos IS '환경 점검 사진(귀 왼쪽·오른쪽, 손목). 대상자·단계마다 한 장';
COMMENT ON COLUMN env_check_photos.stage IS '단계(EAR_LEFT 왼쪽 귀, EAR_RIGHT 오른쪽 귀, WRIST 손목)';
COMMENT ON COLUMN env_check_photos.storage_key IS '파일 저장소 키(보관 기간 뒤에도 남겨 두고 deleted_at 으로 지웠음을 표시)';
COMMENT ON COLUMN env_check_photos.captured_at IS '찍은(올린) 시각. 다시 찍으면 갱신';
COMMENT ON COLUMN env_check_photos.deleted_at IS '보관 기간이 지나 파일을 지운 시각';
