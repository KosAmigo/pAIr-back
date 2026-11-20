-- 과제 내용(설명·요구사항·파일·후속 질문)을 classpath:tasks 폴더에서 DB 로 옮긴다 (2026-11-20).
-- 과제 행의 내용은 이제 tasks / task_files / task_questions 가 맡는다. 실전(LIVE) 복사본은 내용 행을 따로 두지 않고
-- parent_task_id 의 원본 내용을 쓴다. 아래 시드는 폴더에서 생성했다.

ALTER TABLE tasks ADD COLUMN settings JSONB;
COMMENT ON COLUMN tasks.settings IS '응시 화면 설정(JSON): openFile 처음 열 파일, editableFile 문서 편집기로 여는 파일, requiredHeadings 필수 제목, d4Minutes D4 환산 구간 상한(분)';

-- order-deadlock
UPDATE tasks SET description = '어제 21시부터 주문 생성이 간헐적으로 3초 넘게 멈췄다가 LockTimeoutException으로 실패하고 있습니다. 같은 시간대에 장바구니에 상품 여러 개를 담은 주문이 몰렸습니다.
src/shop 아래 코드와 logs/app.log를 살펴보고, 내장 AI를 활용해 원인을 찾아 수정하세요. 실행 버튼을 누르면 test 폴더의 JUnit 테스트가 실행됩니다.',
    requirements = '장애의 근본 원인을 찾아 코드로 수정한다.
주문이 실패하면 재고는 주문 전 상태와 같아야 한다.
수정이 올바른지 확인할 수 있는 JUnit 테스트를 1개 이상 추가한다.
NOTES.md에 원인과 판단 근거를 3줄 이내로 정리한다.',
    settings = '{"openFile":"src/shop/OrderService.java","editableFile":null,"requiredHeadings":[],"d4Minutes":[20,30,40,50]}'::jsonb
WHERE code = 'order-deadlock';
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'NOTES.md', '# 원인

# 판단 근거
', 'text/markdown', 26, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'logs/app.log', '2026-11-10 21:02:09.114 INFO  [order-7]  POST /orders user=4812 items=[10:1, 22:2]
2026-11-10 21:02:09.131 INFO  [order-9]  POST /orders user=5120 items=[22:1, 10:1]
2026-11-10 21:02:12.140 ERROR [order-9]  POST /orders 500 shop.LockTimeoutException: Lock wait timeout exceeded (stock.product_id=10)
2026-11-10 21:02:12.141 ERROR [order-7]  POST /orders 500 shop.LockTimeoutException: Lock wait timeout exceeded (stock.product_id=22)
2026-11-10 21:02:15.402 INFO  [order-3]  POST /orders user=771 items=[31:1]
2026-11-10 21:02:15.459 INFO  [order-3]  POST /orders 200 (57ms)
2026-11-10 21:03:40.010 INFO  [order-2]  POST /orders user=903 items=[10:2, 31:1, 22:1]
2026-11-10 21:03:40.022 INFO  [order-5]  POST /orders user=1288 items=[31:1, 10:1]
2026-11-10 21:03:43.031 ERROR [order-5]  POST /orders 500 shop.LockTimeoutException: Lock wait timeout exceeded (stock.product_id=10)
2026-11-10 21:03:43.188 INFO  [order-2]  POST /orders 200 (3178ms)
2026-11-10 21:05:02.500 WARN  [batch]    stock check: product_id=22 expected=118 actual=116 (diff -2)
2026-11-10 21:05:02.501 WARN  [batch]    stock check: product_id=10 expected=240 actual=239 (diff -1)
', 'text/plain', 1151, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/Db.java', 'package shop;

/** 실제 DB 왕복 시간을 흉내 낸다. 수정 대상이 아니다. */
final class Db {

    private Db() {
    }

    static void simulateLatency() throws InterruptedException {
        Thread.sleep(50);
    }
}
', 'text/x-java-source', 234, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/InventoryService.java', 'package shop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InventoryService {

    /** 잠금을 기다리는 최대 시간. 운영 DB의 lock wait timeout 과 같은 값이다. */
    static final long LOCK_TIMEOUT_MS = 3000;

    private final Map<Long, Stock> stocks = new ConcurrentHashMap<>();

    public void register(long productId, int count) {
        stocks.put(productId, new Stock(productId, count));
    }

    public int countOf(long productId) {
        return stock(productId).count();
    }

    /** 재고 행을 잠근다. LOCK_TIMEOUT_MS 안에 잠그지 못하면 LockTimeoutException. */
    public void lock(long productId) throws InterruptedException {
        Stock stock = stock(productId);
        if (!stock.lock().tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException(productId);
        }
    }

    public void unlock(long productId) {
        stock(productId).lock().unlock();
    }

    public void decrease(long productId, int quantity) {
        stock(productId).decrease(quantity);
    }

    public void increase(long productId, int quantity) {
        stock(productId).increase(quantity);
    }

    private Stock stock(long productId) {
        Stock stock = stocks.get(productId);
        if (stock == null) {
            throw new IllegalArgumentException("등록되지 않은 상품: " + productId);
        }
        return stock;
    }
}
', 'text/x-java-source', 1500, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/LockTimeoutException.java', 'package shop;

public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(long productId) {
        super("Lock wait timeout exceeded (stock.product_id=" + productId + ")");
    }
}
', 'text/x-java-source', 217, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/Order.java', 'package shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Order {

    private final long userId;
    private final List<OrderItem> items = new ArrayList<>();

    public Order(long userId) {
        this.userId = userId;
    }

    void add(OrderItem item) {
        items.add(item);
    }

    public long userId() {
        return userId;
    }

    public List<OrderItem> items() {
        return Collections.unmodifiableList(items);
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::quantity).sum();
    }
}
', 'text/x-java-source', 605, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/OrderItem.java', 'package shop;

public record OrderItem(long productId, int quantity) {
}
', 'text/x-java-source', 73, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/OrderService.java', 'package shop;

import java.util.ArrayList;
import java.util.List;

public class OrderService {

    private final InventoryService inventory;

    public OrderService(InventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * 주문을 만든다. 상품마다 재고를 잠그고 차감한 뒤, 끝나면 잠금을 푼다.
     */
    public Order createOrder(long userId, List<OrderItem> items) throws InterruptedException {
        Order order = new Order(userId);
        List<Long> locked = new ArrayList<>();
        try {
            for (OrderItem item : items) {
                // 상품마다 재고를 잠그고 차감한다
                inventory.lock(item.productId());
                locked.add(item.productId());
                Db.simulateLatency();
                inventory.decrease(item.productId(), item.quantity());
                order.add(item);
            }
            return order;
        } finally {
            for (Long productId : locked) {
                inventory.unlock(productId);
            }
        }
    }
}
', 'text/x-java-source', 1084, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/OutOfStockException.java', 'package shop;

public class OutOfStockException extends RuntimeException {

    public OutOfStockException(long productId, int count, int requested) {
        super("재고 부족: product=" + productId + ", 남은 수량=" + count + ", 요청=" + requested);
    }
}
', 'text/x-java-source', 268, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'src/shop/Stock.java', 'package shop;

import java.util.concurrent.locks.ReentrantLock;

public class Stock {

    private final long productId;
    private final ReentrantLock lock = new ReentrantLock();
    private int count;

    public Stock(long productId, int count) {
        this.productId = productId;
        this.count = count;
    }

    public long productId() {
        return productId;
    }

    public int count() {
        return count;
    }

    ReentrantLock lock() {
        return lock;
    }

    void decrease(int quantity) {
        if (count < quantity) {
            throw new OutOfStockException(productId, count, quantity);
        }
        count -= quantity;
    }

    void increase(int quantity) {
        count += quantity;
    }
}
', 'text/x-java-source', 744, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'test/shop/OrderServiceTest.java', 'package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class OrderServiceTest {

    @Test
    void createOrder_singleItem() throws Exception {
        InventoryService inventory = new InventoryService();
        inventory.register(10L, 5);
        OrderService orderService = new OrderService(inventory);

        Order order = orderService.createOrder(1L, List.of(new OrderItem(10L, 1)));

        assertEquals(1, order.totalQuantity());
        assertEquals(4, inventory.countOf(10L));
    }
}
', 'text/x-java-source', 579, 'VISIBLE', FALSE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'answer.md', '# 개발 과제 정답 설명 (응시자에게 보이지 않음, 채점 모델 참고용)

## 핵심 원인 1: 잠금 순서 불일치로 인한 교착 (가장 중요)
- `OrderService.createOrder`가 주문 항목 순서대로 상품 잠금을 잡는다.
- 주문 A는 상품 10 → 22, 주문 B는 22 → 10 순서로 잠그면, 서로 상대가 가진 잠금을 기다리다 둘 다 3초 뒤 `LockTimeoutException`으로 실패한다.
- 로그 근거: 21:02:09에 `items=[10:1, 22:2]`와 `items=[22:1, 10:1]` 주문이 거의 동시에 들어오고, 3초 뒤 두 주문이 각각 10, 22에서 타임아웃.
- 해결: 상품 ID 정렬 등으로 모든 주문이 같은 순서로 잠근다. 타임아웃을 늘리거나 풀을 키우는 것은 증상 완화일 뿐이다.

## 핵심 원인 2: 실패 시 재고를 되돌리지 않음
- 잠금을 하나 잡고 바로 차감하므로, 뒤 상품에서 실패하면 앞 상품 재고만 줄어든 채 주문이 실패한다.
- 로그 근거: `stock check: product_id=22 expected=118 actual=116`, `product_id=10 expected=240 actual=239`.
- 해결: 모든 잠금을 먼저 얻은 뒤 차감하거나, 실패 시 차감한 재고를 원복한다.

## 원인 설명 판정 기준 (D4)
- 원인 1(잠금 순서 불일치, 교착, 역순 주문)을 설명했으면 핵심 원인을 맞힌 것으로 본다.
- 원인 2만 설명하거나 "타임아웃이 짧아서", "예외 처리가 없어서"처럼 증상만 설명하면 핵심 원인을 놓친 것이다.
', 'text/markdown', 1501, 'HIDDEN', TRUE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'shop/HiddenOrderTest.java', 'package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/** 채점용 숨은 테스트. 응시자에게 보이지 않는다. */
class HiddenOrderTest {

    /** 필수 1: 상품 순서가 반대인 주문이 동시에 와도 교착 없이 모두 끝난다. */
    @Test
    void required_reverseOrderConcurrent_noDeadlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            InventoryService inventory = new InventoryService();
            inventory.register(10L, 1000);
            inventory.register(22L, 1000);
            OrderService service = new OrderService(inventory);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                int rounds = 10;
                for (int i = 0; i < rounds; i++) {
                    CountDownLatch start = new CountDownLatch(1);
                    Future<Order> a = pool.submit(() -> {
                        start.await();
                        return service.createOrder(1L, List.of(new OrderItem(10L, 1), new OrderItem(22L, 1)));
                    });
                    Future<Order> b = pool.submit(() -> {
                        start.await();
                        return service.createOrder(2L, List.of(new OrderItem(22L, 1), new OrderItem(10L, 1)));
                    });
                    start.countDown();
                    a.get();
                    b.get();
                }
                assertEquals(1000 - rounds * 2, inventory.countOf(10L));
                assertEquals(1000 - rounds * 2, inventory.countOf(22L));
            } finally {
                pool.shutdownNow();
            }
        });
    }

    /** 필수 2: 한 주문에 같은 상품이 두 번 들어와도 수량만큼 정확히 차감된다. */
    @Test
    void required_duplicateItems_decreaseTotal() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            InventoryService inventory = new InventoryService();
            inventory.register(10L, 5);
            OrderService service = new OrderService(inventory);

            Order order = service.createOrder(1L, List.of(new OrderItem(10L, 1), new OrderItem(10L, 2)));

            assertEquals(3, order.totalQuantity());
            assertEquals(2, inventory.countOf(10L));
        });
    }

    /** 필수 3: 재고가 부족해 주문이 실패하면 다른 상품 재고도 원래대로 돌아온다. */
    @Test
    void required_outOfStock_rollsBackOtherItems() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            InventoryService inventory = new InventoryService();
            inventory.register(10L, 5);
            inventory.register(22L, 1);
            OrderService service = new OrderService(inventory);

            assertThrows(OutOfStockException.class,
                    () -> service.createOrder(1L, List.of(new OrderItem(10L, 2), new OrderItem(22L, 3))));

            assertEquals(5, inventory.countOf(10L));
            assertEquals(1, inventory.countOf(22L));
        });
    }

    /** 필수 4: 실패한 주문 뒤에도 잠금이 남지 않아 다음 주문이 바로 처리된다. */
    @Test
    void required_locksReleasedAfterFailure() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            InventoryService inventory = new InventoryService();
            inventory.register(10L, 5);
            inventory.register(22L, 0);
            OrderService service = new OrderService(inventory);

            assertThrows(OutOfStockException.class,
                    () -> service.createOrder(1L, List.of(new OrderItem(10L, 1), new OrderItem(22L, 1))));

            long started = System.nanoTime();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Order next = pool.submit(() -> service.createOrder(2L, List.of(new OrderItem(10L, 1)))).get();
                assertEquals(1, next.totalQuantity());
            } finally {
                pool.shutdownNow();
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000, "다음 주문이 잠금 때문에 지연됨");
        });
    }

    /** 선택: 여러 스레드가 겹치는 상품을 섞어 주문해도 총 재고가 맞다. */
    @Test
    void optional_manyThreads_stockConsistent() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            InventoryService inventory = new InventoryService();
            long[] ids = {10L, 22L, 31L};
            for (long id : ids) inventory.register(id, 10_000);
            OrderService service = new OrderService(inventory);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Order>> futures = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    long a = ids[i % 3];
                    long b = ids[(i + 1) % 3];
                    long c = ids[(i + 2) % 3];
                    long user = i;
                    futures.add(pool.submit(() -> service.createOrder(user,
                            List.of(new OrderItem(a, 1), new OrderItem(b, 1), new OrderItem(c, 1)))));
                }
                for (Future<Order> f : futures) f.get();
                for (long id : ids) assertEquals(10_000 - 24, inventory.countOf(id));
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
', 'text/x-java-source', 5931, 'HIDDEN', TRUE
FROM tasks WHERE code = 'order-deadlock'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 1, 'AI 답변 중 그대로 믿지 않고 직접 확인한 것은 무엇이고, 어떻게 확인했나요? (예: 테스트 실행, 로그 대조)'
FROM tasks WHERE code = 'order-deadlock'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 1);
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 2, 'AI가 제안한 내용 중 채택하지 않은 것이 있다면 무엇이고, 이유는 무엇인가요?'
FROM tasks WHERE code = 'order-deadlock'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 2);
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 3, '동료가 같은 결과를 얻으려면 AI에게 어떤 지시를 어떤 순서로 주면 되나요?'
FROM tasks WHERE code = 'order-deadlock'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 3);

-- pricing-plan
UPDATE tasks SET description = '영상 구독 서비스 무비랩의 월 이탈률이 5월 4.1%에서 10월 6.8%로 올랐습니다. 경영진은 월 5,900원 라이트 요금제 출시를 검토하고 있습니다.
자료 폴더의 설문 요약과 매출 데이터를 살펴보고, 내장 AI를 활용해 라이트 요금제 출시 여부에 대한 1페이지 기획안을 기획안.md에 작성하세요.',
    requirements = '출시 찬성, 반대, 조건부 중 결론을 밝히고 근거를 3개 이상 제시한다.
근거에 쓰는 수치는 제공 자료와 대조해 검증한다.
예상 리스크와 대응 방안을 1개 이상 포함한다.',
    settings = '{"openFile":"기획안.md","editableFile":"기획안.md","requiredHeadings":["## 결론","## 근거","## 리스크"],"d4Minutes":[20,30,40,50]}'::jsonb
WHERE code = 'pricing-plan';
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, '기획안.md', '# 저가 요금제(라이트) 출시 기획안

## 결론

## 근거

## 예상 효과

## 리스크와 대응
', 'text/markdown', 111, 'VISIBLE', FALSE
FROM tasks WHERE code = 'pricing-plan'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, '자료/요금제별_매출.csv', '월,요금제,월 요금(원),월말 가입자 수,월 매출(원),월 이탈률(%)
2026-05,베이직,9900,63800,631620000,4.6
2026-05,스탠다드,13900,29200,405880000,3.6
2026-05,프리미엄,17900,10400,186160000,2.9
2026-06,베이직,9900,63500,628650000,5.0
2026-06,스탠다드,13900,29000,403100000,3.8
2026-06,프리미엄,17900,10350,185265000,3.0
2026-07,베이직,9900,63100,624690000,5.6
2026-07,스탠다드,13900,28800,400320000,4.1
2026-07,프리미엄,17900,10300,184370000,3.2
2026-08,베이직,9900,62800,621720000,6.3
2026-08,스탠다드,13900,28500,396150000,4.5
2026-08,프리미엄,17900,10200,182580000,3.4
2026-09,베이직,9900,62400,617760000,7.1
2026-09,스탠다드,13900,28300,393370000,4.9
2026-09,프리미엄,17900,10100,180790000,3.7
2026-10,베이직,9900,62000,613800000,7.9
2026-10,스탠다드,13900,28000,389200000,5.4
2026-10,프리미엄,17900,10000,179000000,4.0
', 'text/csv', 906, 'VISIBLE', FALSE
FROM tasks WHERE code = 'pricing-plan'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, '자료/이탈_설문_요약.md', '# 이탈 및 요금제 설문 요약 (2026년 10월)

조사 대상 서비스: 영상 구독 서비스 "무비랩"
조사 기간: 2026-10-14 ~ 10-25, 온라인 설문

## 1. 최근 3개월 이탈 고객 설문 (응답 1,200명)

| 이탈 사유 (단일 선택) | 비율 |
| --- | --- |
| 가격 부담 | 38% |
| 볼 만한 콘텐츠 부족 | 27% |
| 다른 서비스로 이동 | 18% |
| 일시적으로 이용 중단 | 11% |
| 기타 | 6% |

- "월 5,900원 라이트 요금제(광고 없음, HD, 동시 접속 1대)가 있었다면 계속 이용했을까?"
  - 가격 부담을 이유로 이탈한 응답자 중 41%가 "계속 이용했을 것"이라고 답함
  - 다른 사유로 이탈한 응답자 중에서는 9%

## 2. 현재 이용자 설문 (응답 2,000명)

- 만족도(5점 척도): 베이직 3.4, 스탠다드 3.9, 프리미엄 4.2
- "월 5,900원 라이트 요금제가 나오면 바꾸시겠습니까?"

| 현재 요금제 | 라이트로 바꾸겠다 | 그대로 유지 | 모르겠다 |
| --- | --- | --- | --- |
| 베이직 (9,900원) | 34% | 51% | 15% |
| 스탠다드 (13,900원) | 9% | 80% | 11% |
| 프리미엄 (17,900원) | 2% | 94% | 4% |

## 3. 참고

- 요금제별 가입자와 매출은 `요금제별_매출.csv`를 참고
- 설문 응답은 의향이며, 실제 전환율은 보통 의향보다 낮게 나타난다(사내 과거 사례 기준 의향의 약 50~60%)
', 'text/markdown', 1394, 'VISIBLE', FALSE
FROM tasks WHERE code = 'pricing-plan'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_files (task_id, path, content, mime_type, size_bytes, visibility, read_only)
SELECT task_id, 'checklist.md', '# 비개발 과제 채점 체크리스트 (응시자에게 보이지 않음)

## 필수 항목 (D1)
1. 결론: 출시 찬성, 반대, 조건부 중 하나를 분명하게 밝혔다.
2. 근거 3개: 근거가 3개 이상이고, 각 근거가 제공 자료(설문 요약, 매출 CSV)의 수치를 인용한다.
3. 잠식 효과: 기존 베이직, 스탠다드 이용자가 라이트로 옮겨 가는 매출 감소(카니발라이제이션)를 다룬다.
   - 참고 계산: 10월 기준 베이직 62,000명 × 34% × (9,900 - 5,900) ≈ 월 8,430만 원 감소 가능,
     이탈 방지 효과는 월 이탈 약 6,810명 × 38% × 41% ≈ 1,060명 × 5,900원 ≈ 월 630만 원.
   - 의향을 실제 전환율(50~60%)로 보정했는지는 가점 요소.
4. 리스크와 대응: 리스크를 1개 이상 들고 대응 방안을 제시한다.
5. 수치 정확성: 문서의 수치가 자료와 일치한다. 자료에 없는 수치(시장 규모, 경쟁사 가격 등)를 근거 없이 쓰지 않았다.

## 좋은 답의 예
- 라이트를 그대로 출시하면 잠식 손실이 이탈 방지 효과보다 10배 이상 커서 매출이 줄어든다.
- 그래서 반대하거나, 기능 제한(광고 포함, 화질 제한, 신규 가입자 전용 등)으로 잠식을 줄이는 조건부 출시를 제안한다.

## 흔한 함정
- AI가 만든 그럴듯한 수치(예: "업계 평균 이탈률 3%", "라이트 도입 시 이탈률 30% 감소")를 그대로 쓴다.
- 이탈 고객 설문만 보고 찬성 결론을 낸다(현재 이용자 설문의 잠식 신호를 놓친다).
', 'text/markdown', 1581, 'HIDDEN', TRUE
FROM tasks WHERE code = 'pricing-plan'
ON CONFLICT (task_id, path) DO NOTHING;
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 1, 'AI 답변 중 그대로 믿지 않고 직접 확인한 것은 무엇이고, 어떻게 확인했나요? (예: 수치 검산, 자료 대조)'
FROM tasks WHERE code = 'pricing-plan'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 1);
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 2, 'AI가 제시한 내용 중 채택하지 않은 것이 있다면 무엇이고, 이유는 무엇인가요?'
FROM tasks WHERE code = 'pricing-plan'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 2);
INSERT INTO task_questions (task_id, stage, question_type, seq, content)
SELECT task_id, 'FOLLOWUP', 'ESSAY', 3, '동료가 같은 결론을 얻으려면 AI에게 어떤 지시를 어떤 순서로 주면 되나요?'
FROM tasks WHERE code = 'pricing-plan'
  AND NOT EXISTS (SELECT 1 FROM task_questions x WHERE x.task_id = tasks.task_id AND x.stage = 'FOLLOWUP' AND x.seq = 3);
