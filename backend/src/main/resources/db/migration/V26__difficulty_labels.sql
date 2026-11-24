-- 난이도 이름 변경: 초급/중급/고급 -> 쉬움/보통/어려움 (응시자 수준으로 오해되지 않게)
UPDATE tasks SET difficulty = CASE difficulty
    WHEN '초급' THEN '쉬움'
    WHEN '중급' THEN '보통'
    WHEN '고급' THEN '어려움'
    ELSE difficulty END;

COMMENT ON COLUMN tasks.difficulty IS '난이도: 쉬움/보통/어려움 (제한시간 20/40/60분으로 정함)';
