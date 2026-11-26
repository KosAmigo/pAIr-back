-- 부하 테스트 사용자(1001~1100)와 크레딧. 여러 번 실행해도 된다(사용자는 한 번만 만들고, 크레딧은 실행마다 넉넉히 채운다).
INSERT INTO users (user_id, email, password_hash, name, member_status, role)
SELECT g, 'load' || g || '@pair.test', '$2a$10$loadtestPlaceholderHashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', '부하' || g, 'ACTIVE', 'USER'
FROM generate_series(1001, 1100) g
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO credit_ledger (user_id, amount, balance_after, reason, note)
SELECT u.user_id, 10000,
       coalesce((SELECT l.balance_after FROM credit_ledger l WHERE l.user_id = u.user_id ORDER BY l.ledger_id DESC LIMIT 1), 0) + 10000,
       'ADJUST', '부하 테스트'
FROM users u WHERE u.user_id BETWEEN 1001 AND 1100;
