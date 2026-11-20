-- 시험 중 연결이 끊겼을 때의 유예(5분). 시험 화면이 보내는 신호의 마지막 시각을 남기고,
-- 유예 안에 돌아오지 못하면 입장 링크를 만료시킨다. 끊김·만료는 감독 이벤트로 남는다.
ALTER TABLE exam_participants
    ADD COLUMN last_seen_at     TIMESTAMPTZ,
    ADD COLUMN link_expired_at  TIMESTAMPTZ;
COMMENT ON COLUMN exam_participants.last_seen_at IS '시험 화면이 마지막으로 연결을 알려 온 시각(시작 후)';
COMMENT ON COLUMN exam_participants.link_expired_at IS '유예 시간 안에 돌아오지 않아 입장 링크가 만료된 시각(비어 있으면 유효)';

INSERT INTO proctor_event_types (code, source, label, severity) VALUES
    ('CONNECTION_LOST', 'BROWSER', '시험 화면 연결이 끊겼다가 돌아왔습니다',                  'MEDIUM'),
    ('LINK_EXPIRED',    'BROWSER', '유예 시간 안에 돌아오지 않아 입장 링크가 만료되었습니다',    'HIGH');
