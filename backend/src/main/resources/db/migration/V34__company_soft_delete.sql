-- 관리자가 회사를 삭제한다. 회차·응시·감독 기록·결제가 회사와 멤버를 참조하므로 행은 남기고 시각만 적는다.
ALTER TABLE companies ADD COLUMN deleted_at TIMESTAMPTZ;
COMMENT ON COLUMN companies.deleted_at IS '관리자가 삭제한 시각. 값이 있으면 관리자 회사 목록에서 숨기고 새 회차·초대를 막는다. 멤버는 같은 시각에 탈퇴 처리된다';
