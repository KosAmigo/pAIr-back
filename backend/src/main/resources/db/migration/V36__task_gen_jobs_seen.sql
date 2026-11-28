-- 만드는 중인 과제 화면: 끝난 작업을 관리자가 '확인'(상세를 열거나 모두 확인)한 시각. 확인한 끝난 작업은 작업 목록에서만 빠진다
-- (만든 과제·생성 기록·통계는 그대로). 컬럼 추가만 한다(팀 공용 RDS).
ALTER TABLE task_gen_jobs ADD COLUMN seen_at TIMESTAMPTZ NULL;
