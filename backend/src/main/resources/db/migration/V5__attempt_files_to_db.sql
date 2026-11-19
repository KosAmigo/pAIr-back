-- 응시 기록을 파일(pilot-data/attempts/{id}/)에서 DB 로 옮긴다 (2026-11-19 결정).
-- 작업 파일은 attempt_files, 대화는 chat_turns, 작업 기록은 attempt_events(V1 그대로)에 저장한다.
-- 저장할 때마다 남기던 파일 스냅샷은 attempt_events 의 SAVE payload(content)에 함께 남긴다.
-- 여기서는 백엔드가 쓰는데 ERD 에 없던 값만 더한다. 채점 툴 실험(grader/samples, grader/runs)은 계속 파일이다.

-- 1. 응시 상태 중 ERD 에 없던 값
ALTER TABLE attempts
    ADD COLUMN provider           VARCHAR(20),
    ADD COLUMN first_valid_at     TIMESTAMPTZ,
    ADD COLUMN error              TEXT,
    ADD COLUMN followup_questions JSONB,
    ADD COLUMN followup_answers   JSONB;
COMMENT ON COLUMN attempts.provider IS '어시스턴트 제공사(openai/anthropic 등)';
COMMENT ON COLUMN attempts.first_valid_at IS '첫 유효 결과 시각(개발: 고친 코드로 테스트 전부 통과, 비개발: 필수 항목을 채운 첫 저장)';
COMMENT ON COLUMN attempts.error IS '채점 실패 사유(status = ERROR 일 때)';
COMMENT ON COLUMN attempts.followup_questions IS '응시자가 본 후속 질문 문구 목록(과제 질문이 나중에 바뀌어도 채점은 이 문구로)';
COMMENT ON COLUMN attempts.followup_answers IS '후속 질문 답 목록(질문과 같은 순서)';

-- 2. AI 대화 중 ERD 에 없던 값. 채점(R2)에서 AI 요청을 그대로 다시 보내려면 요청 원문이 필요하다
ALTER TABLE chat_turns
    ADD COLUMN provider   VARCHAR(20),
    ADD COLUMN request    JSONB,
    ADD COLUMN latency_ms INT;
COMMENT ON COLUMN chat_turns.provider IS '어시스턴트 제공사(AI 턴만)';
COMMENT ON COLUMN chat_turns.request IS 'AI 에 보낸 요청 원문(모델, 최대 출력, 추론 강도, 시스템 프롬프트, 메시지. AI 턴만)';
COMMENT ON COLUMN chat_turns.latency_ms IS 'AI 응답 시간(ms, AI 턴만)';

-- 3. 채점 리포트. 채점 버전·체크 목록이 아직 파일(grader/versions)이라 scorings 이하 테이블 대신 리포트 JSON 을 통째로 둔다
CREATE TABLE attempt_reports (
    attempt_id  BIGINT       PRIMARY KEY REFERENCES attempts (attempt_id),
    report      JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  attempt_reports IS '채점 리포트(응시당 최신 1건)';
COMMENT ON COLUMN attempt_reports.attempt_id IS '응시 ID';
COMMENT ON COLUMN attempt_reports.report IS '리포트 JSON(총점, 항목별 점수, 체크 결과, 토큰)';
COMMENT ON COLUMN attempt_reports.updated_at IS '저장 시각';
