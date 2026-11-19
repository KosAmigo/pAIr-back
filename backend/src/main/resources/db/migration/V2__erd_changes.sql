-- ERD(V1) 변경 (2026-11-19 결정). ERD 문서(pAIr-docs/설계/erd/pair_schema.sql, 테이블_정의서.md)에도 같은 내용을 반영했다.
--   1~4. 백엔드가 쓰는데 ERD 에 없던 4가지를 더한다
--   5.   리포트 공유 기능을 뺀다
--   6.   자동 제출 표시(attempts.auto_submitted)를 뺀다. 종료 시각 자동 제출 기능은 그대로다

-- 1. 초대 메일 발송 결과. 실패하면 사유를 남겨 담당자가 다시 보낼 수 있게 한다
ALTER TABLE exam_participants
    ADD COLUMN invite_status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (invite_status IN ('PENDING', 'SENT', 'FAILED')),
    ADD COLUMN invite_error  VARCHAR(255);
COMMENT ON COLUMN exam_participants.invite_status IS '초대 메일 발송 결과(PENDING 보내는 중/SENT 보냄/FAILED 실패). 다시 보내면 PENDING';
COMMENT ON COLUMN exam_participants.invite_error IS '초대 메일 실패 사유';

-- 2. 자동 제출 처리 시각. 스케줄러가 같은 회차를 두 번 닫지 않게 하는 표시이자, 종료 시각 대비 처리 지연(공정성 지표)
ALTER TABLE exam_sessions ADD COLUMN closed_at TIMESTAMPTZ;
COMMENT ON COLUMN exam_sessions.closed_at IS '종료 뒤 자동 제출 처리를 마친 시각(비어 있으면 아직 닫히지 않은 회차)';
-- 스케줄러가 "끝났는데 안 닫힌 회차"를 찾는 조회용
CREATE INDEX idx_exam_sessions_open_ends_at ON exam_sessions (ends_at) WHERE closed_at IS NULL AND canceled_at IS NULL;

-- 3·4. 감독 사진 크기(영상 스트리밍 대비 절감량 계산), 서버가 이벤트를 받은 시각
ALTER TABLE proctor_events
    ADD COLUMN snapshot_bytes INT         NOT NULL DEFAULT 0 CHECK (snapshot_bytes >= 0),
    ADD COLUMN received_at    TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN proctor_events.snapshot_bytes IS '감독 사진 크기(바이트, 사진이 없으면 0)';
COMMENT ON COLUMN proctor_events.received_at IS '서버가 이벤트를 받은 시각(started_at 은 응시자 PC 기준 감지 시각)';

-- 5. 리포트 공유 링크는 쓰지 않는다 (CHECK 제약은 컬럼과 함께 지워진다)
ALTER TABLE attempts
    DROP COLUMN share_token_hash,
    DROP COLUMN shared_at;

-- 6. 자동 제출 표시는 쓰지 않는다. 이 컬럼을 쓰던 CHECK 두 개는 컬럼과 함께 지워지므로,
--    "채점 단계면 후속 질문을 마쳤다" 규칙만 다시 건다
ALTER TABLE attempts DROP COLUMN auto_submitted;
ALTER TABLE attempts ADD CONSTRAINT ck_attempts_followups_before_scoring
    CHECK (status IN ('SOLVING', 'FOLLOWUP') OR followups_done_at IS NOT NULL);
