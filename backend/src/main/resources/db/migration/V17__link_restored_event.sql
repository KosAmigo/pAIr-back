-- 담당자가 만료된 입장 링크를 콘솔에서 되살렸을 때 남기는 감독 이벤트. 만료 기록(LINK_EXPIRED)은 그대로 두고 따로 남긴다.
INSERT INTO proctor_event_types (code, source, label, severity) VALUES
    ('LINK_RESTORED', 'BROWSER', '담당자가 만료된 입장 링크를 되살렸습니다', 'MEDIUM');
