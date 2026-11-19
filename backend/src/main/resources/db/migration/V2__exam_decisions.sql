-- 기획 결정사항에 맞추기 (docs/검증_결과.md 3절 9가지). ERD: docs/설계/erd/pair_schema.sql
--   1·2. 입장 토큰은 SHA-256 해시만 저장 (원문은 메일에만). 다시 보내면 새 토큰
--   3.   대상자 CSV 원본 보관 위치
--   4.   시작 하루 전 알림 메일 발송 시각
--   5.   회차 취소는 지우지 않고 canceled_at 을 남긴다 (시작 전만)
--   6.   사진 보관은 회차 종료 30일 뒤까지 (조회 조건만 바뀌므로 스키마는 인덱스만)
--   7.   감독 이벤트 담당자 판정 (OK/SUSPECT)
--   8.   exam_session_tasks.task_usage, proctor_event_types 코드표
--        attempts.participant_id 는 attempts 가 DB 로 옮겨질 때 바꾼다. 그때까지 participant_attempts 가 그 역할을 한다
--   9.   과제별 제출은 응시(attempt) 단위라 스키마 변경 없음

-- 1·2. 입장 토큰 해시. 이미 보낸 링크도 계속 쓰이도록 원문을 해시로 바꿔 옮긴다
ALTER TABLE exam_participants ADD COLUMN invite_token_hash char(64);
UPDATE exam_participants SET invite_token_hash = encode(sha256(convert_to(invite_token, 'UTF8')), 'hex');
ALTER TABLE exam_participants ALTER COLUMN invite_token_hash SET NOT NULL;
ALTER TABLE exam_participants ADD CONSTRAINT uq_exam_participants_invite_token_hash UNIQUE (invite_token_hash);
ALTER TABLE exam_participants DROP COLUMN invite_token;

-- 4. 하루 전 알림
ALTER TABLE exam_participants ADD COLUMN reminded_at timestamptz;

-- 3·5. CSV 원본, 취소
ALTER TABLE exam_sessions ADD COLUMN participants_csv_key varchar(255);
ALTER TABLE exam_sessions ADD COLUMN canceled_at timestamptz;
ALTER TABLE exam_sessions ADD CONSTRAINT ck_exam_sessions_canceled_before_start
    CHECK (canceled_at IS NULL OR canceled_at < starts_at);

-- 8. 회차에는 기업용(LIVE) 과제만 건다. tasks 가 DB 로 옮겨지면 (task_id, task_usage) 외래 키를 건다
ALTER TABLE exam_session_tasks ADD COLUMN task_usage varchar(10) NOT NULL DEFAULT 'LIVE'
    CHECK (task_usage = 'LIVE');

-- 8. 감독 이벤트 종류 코드표. 감지 종류가 늘면 행만 추가한다 (ProctorEvent.Type 과 같은 이름)
CREATE TABLE proctor_event_types (
    code      varchar(20)  PRIMARY KEY,
    source    varchar(10)  NOT NULL CHECK (source IN ('BROWSER', 'PC_CAM', 'PHONE_CAM')),
    label     varchar(100) NOT NULL,
    severity  varchar(10)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH'))
);
INSERT INTO proctor_event_types (code, source, label, severity) VALUES
    ('TAB_HIDDEN',         'BROWSER',   '다른 탭으로 이동했습니다',               'MEDIUM'),
    ('WINDOW_BLUR',        'BROWSER',   '다른 창으로 이동했습니다',               'MEDIUM'),
    ('MOUSE_LEAVE',        'BROWSER',   '마우스가 시험 화면 밖으로 나갔습니다',      'LOW'),
    ('PASTE',              'BROWSER',   '외부에서 붙여 넣었습니다',               'HIGH'),
    ('FULLSCREEN_EXIT',    'BROWSER',   '전체 화면을 나갔습니다',                 'MEDIUM'),
    ('NO_FACE',            'PC_CAM',    '얼굴이 화면에 보이지 않습니다',           'HIGH'),
    ('MULTI_FACE',         'PC_CAM',    '여러 사람이 감지되었습니다',             'HIGH'),
    ('HEAD_TURN',          'PC_CAM',    '고개를 화면 밖으로 돌렸습니다',           'MEDIUM'),
    ('HEAD_DOWN',          'PC_CAM',    '고개를 오래 숙이고 있습니다',             'LOW'),
    ('GAZE_AWAY',          'PC_CAM',    '시선이 화면 밖을 향하고 있습니다',         'LOW'),
    ('TORSO_SHIFT',        'PC_CAM',    '자리에서 크게 움직였습니다',              'LOW'),
    ('TORSO_TURN',         'PC_CAM',    '몸을 돌리거나 화면에서 멀어졌습니다',       'MEDIUM'),
    ('HAND_MISSING',       'PHONE_CAM', '손이 키보드에서 벗어났습니다',            'MEDIUM'),
    ('HAND_MOVED',         'PHONE_CAM', '손이 평소 위치에서 크게 벗어났습니다',      'LOW'),
    ('ARM_MOVE',           'PHONE_CAM', '팔을 크게 움직였습니다',                'LOW'),
    ('PHONE',              'PHONE_CAM', '휴대폰이 감지되었습니다',               'HIGH'),
    ('EXTRA_SCREEN',       'PHONE_CAM', '다른 화면(모니터·노트북)이 감지되었습니다', 'HIGH'),
    ('BOOK',               'PHONE_CAM', '책·자료가 감지되었습니다',              'MEDIUM'),
    ('PHONE_DISCONNECTED', 'PHONE_CAM', '휴대폰 캠 연결이 끊겼습니다',            'MEDIUM');
ALTER TABLE proctor_events ADD CONSTRAINT fk_proctor_events_type
    FOREIGN KEY (type) REFERENCES proctor_event_types (code);

-- 7. 담당자 판정. reviewed_by 는 users 가 DB 로 옮겨지면 외래 키를 건다
ALTER TABLE proctor_events ADD COLUMN review varchar(10) CHECK (review IN ('OK', 'SUSPECT'));
ALTER TABLE proctor_events ADD COLUMN review_note text;
ALTER TABLE proctor_events ADD COLUMN reviewed_by bigint;
ALTER TABLE proctor_events ADD COLUMN reviewed_at timestamptz;
ALTER TABLE proctor_events ADD CONSTRAINT ck_proctor_events_review
    CHECK ((review IS NULL) = (reviewed_by IS NULL) AND (review IS NULL) = (reviewed_at IS NULL));

-- 3·6. 사진과 대상자 CSV 를 한 저장소에 둔다: 키에 폴더를 붙인다 (exam-snapshots/{회차}/{이벤트}.jpg)
UPDATE proctor_events SET snapshot_key = 'exam-snapshots/' || snapshot_key WHERE snapshot_key IS NOT NULL;

-- 6. 사진 정리는 이제 회차 종료 시각 기준으로 찾는다 (받은 시각 인덱스는 쓰지 않음)
DROP INDEX idx_proctor_events_snapshot;
