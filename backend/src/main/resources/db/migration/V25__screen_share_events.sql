-- 화면 공유 이벤트(공유 중단, 전체 화면이 아닌 창·탭 공유, 공유 재시작)를 감독 이벤트로 저장한다.
-- 응시자 PC 브라우저가 감지해서 보내며, 사진은 붙이지 않는다 (종류와 시각만 기록).
ALTER TABLE proctor_event_types DROP CONSTRAINT proctor_event_types_source_check;
ALTER TABLE proctor_event_types
    ADD CONSTRAINT proctor_event_types_source_check CHECK (source IN ('BROWSER', 'PC_CAM', 'PHONE_CAM', 'SCREEN'));
COMMENT ON COLUMN proctor_event_types.source IS '감지 위치(BROWSER 브라우저/PC_CAM 노트북 캠/PHONE_CAM 휴대폰 캠/SCREEN PC 화면 공유)';

INSERT INTO proctor_event_types (code, source, label, severity) VALUES
    ('SCREEN_STOPPED',   'SCREEN', 'PC 화면 공유가 중단되었습니다',             'HIGH'),
    ('SCREEN_PARTIAL',   'SCREEN', '전체 화면이 아닌 창·탭만 공유했습니다',       'MEDIUM'),
    ('SCREEN_RESTARTED', 'SCREEN', 'PC 화면 공유를 다시 시작했습니다',           'LOW');
