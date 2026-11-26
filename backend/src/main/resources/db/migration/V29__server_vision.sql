-- 서버 추론 감독: 회차별 스위치, 새 이벤트 유형, 응시자 불투명 키, 웹캠·휴대폰 사진 상시 저장 묶음 색인, 열람 기록. 추가만 하고 지우거나 바꾸지 않는다.
ALTER TABLE exam_sessions ADD COLUMN server_vision BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN exam_sessions.server_vision IS '서버 추론 감독 사용 여부(백엔드 스위치 PAIR_SERVER_VISION_ENABLED 도 켜져 있어야 함)';

ALTER TABLE proctor_event_types DROP CONSTRAINT proctor_event_types_source_check;
ALTER TABLE proctor_event_types
    ADD CONSTRAINT proctor_event_types_source_check CHECK (source IN ('BROWSER', 'PC_CAM', 'PHONE_CAM', 'SCREEN', 'SERVER'));
COMMENT ON COLUMN proctor_event_types.source IS '감지 위치(BROWSER 브라우저/PC_CAM 노트북 캠/PHONE_CAM 휴대폰 캠/SCREEN PC 화면 공유/SERVER 서버가 직접 남기는 기록)';

INSERT INTO proctor_event_types (code, source, label, severity) VALUES
    ('HANDS_OUT',          'PHONE_CAM', '두 손이 키보드·마우스 영역 밖에 있습니다',     'MEDIUM'),
    ('HAND_OUT_ONE',       'PHONE_CAM', '한 손이 키보드·마우스 영역 밖에 오래 있습니다', 'LOW'),
    ('TABLET',             'PHONE_CAM', '태블릿이 감지되었습니다',                     'HIGH'),
    ('NOTE',               'PHONE_CAM', '메모(종이)가 감지되었습니다',                  'MEDIUM'),
    ('SMARTWATCH',         'PHONE_CAM', '스마트워치가 감지되었습니다',                  'MEDIUM'),
    ('PHONE_MOVED',        'PHONE_CAM', '휴대폰 위치가 바뀌었습니다',                   'LOW'),
    ('SUPERVISION_PAUSED', 'SERVER',    'AI 감독이 일시 중단되었습니다(서버 상태)',       'LOW');

-- 응시자 불투명 키: 추론 서버에는 이 키만 알린다(이름·메일·대상자 번호는 알리지 않음). 처음 필요할 때 만든다.
ALTER TABLE exam_participants ADD COLUMN vision_key VARCHAR(22);
ALTER TABLE exam_participants ADD CONSTRAINT uq_participant_vision_key UNIQUE (vision_key);
COMMENT ON COLUMN exam_participants.vision_key IS '추론 서버용 불투명 키(22자 base64url, 무작위 16바이트)';

-- 사진 상시 저장 묶음 색인: 채널마다 10초 정렬 구간 하나가 한 행. 파일은 파일 저장소(exam-stream/...)에 있다.
CREATE TABLE proctor_stream_packs (
    pack_id        BIGSERIAL PRIMARY KEY,
    session_id     BIGINT       NOT NULL REFERENCES exam_sessions (session_id),
    participant_id BIGINT       NOT NULL REFERENCES exam_participants (participant_id),
    channel        VARCHAR(8)   NOT NULL CHECK (channel IN ('WEBCAM', 'PHONE')),
    start_at       TIMESTAMPTZ  NOT NULL,
    end_at         TIMESTAMPTZ  NOT NULL,
    frame_count    SMALLINT     NOT NULL CHECK (frame_count BETWEEN 1 AND 10),
    size_bytes     INT          NOT NULL,
    storage_key    VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT uq_stream_pack UNIQUE (participant_id, channel, start_at)
);
CREATE INDEX idx_stream_packs_session ON proctor_stream_packs (session_id) WHERE deleted_at IS NULL;
COMMENT ON TABLE proctor_stream_packs IS '사진 상시 저장 묶음 색인 (웹캠·휴대폰, 10초 구간 1행)';

-- 감독 중단 구간 사진 열람 기록: 누가·언제·누구의·어느 채널·어느 범위·몇 장. 감독관은 지울 수 없다.
CREATE TABLE proctor_stream_access_logs (
    log_id         BIGSERIAL PRIMARY KEY,
    session_id     BIGINT      NOT NULL REFERENCES exam_sessions (session_id),
    participant_id BIGINT      NOT NULL REFERENCES exam_participants (participant_id),
    event_id       BIGINT      NOT NULL REFERENCES proctor_events (proctor_event_id),
    viewer_id      BIGINT      NOT NULL,
    channel        VARCHAR(8)  NOT NULL,
    range_from     TIMESTAMPTZ NOT NULL,
    range_to       TIMESTAMPTZ NOT NULL,
    frame_count    INT         NOT NULL,
    accessed_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_stream_access_session ON proctor_stream_access_logs (session_id, accessed_at);
COMMENT ON TABLE proctor_stream_access_logs IS '감독 중단 구간 사진 열람 기록(목록·사진 조회마다 1행)';
