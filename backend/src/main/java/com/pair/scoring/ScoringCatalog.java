package com.pair.scoring;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.grader.PromptVersion;
import com.pair.scoring.Rubric.Check;
import com.pair.scoring.db.PromptCheckHint;
import com.pair.scoring.db.PromptCheckHintRepository;
import com.pair.scoring.db.PromptVersionRecord;
import com.pair.scoring.db.PromptVersionRecordRepository;
import com.pair.scoring.db.RubricCheckRecord;
import com.pair.scoring.db.RubricCheckRecordRepository;
import com.pair.scoring.db.RubricItemRecord;
import com.pair.scoring.db.RubricItemRecordRepository;

/**
 * 코드의 채점 기준(Rubric)과 프롬프트 버전(파일)을 DB 의 채점 기준 테이블에 맞춘다.
 * 항목, 체크는 시작할 때 한 번 upsert 하고, 버전은 처음 쓰일 때 한 번만 넣는다(버전은 바뀌지 않는다).
 * 다른 스레드가 커밋 전의 행을 못 보고 중복으로 넣지 않도록, 락 안에서 별도 트랜잭션으로 커밋까지 끝낸다.
 */
@Component
public class ScoringCatalog {

    private final RubricItemRecordRepository items;
    private final RubricCheckRecordRepository checks;
    private final PromptVersionRecordRepository versions;
    private final PromptCheckHintRepository hints;
    private final TransactionTemplate tx;

    /** Rubric.key(check) → check_id */
    private Map<String, Long> checkIds;

    public ScoringCatalog(RubricItemRecordRepository items, RubricCheckRecordRepository checks,
                          PromptVersionRecordRepository versions, PromptCheckHintRepository hints,
                          PlatformTransactionManager txManager) {
        this.items = items;
        this.checks = checks;
        this.versions = versions;
        this.hints = hints;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        syncRubric();
    }

    /** 항목과 체크를 DB 에 맞추고 check_id 표를 만든다. */
    public synchronized void syncRubric() {
        checkIds = tx.execute(status -> {
            for (int i = 0; i < Rubric.ITEMS.size(); i++) {
                Rubric.Item it = Rubric.ITEMS.get(i);
                RubricItemRecord rec = items.findById(it.key()).orElse(null);
                if (rec == null) items.save(new RubricItemRecord(it.key(), it.name(), it.max(), i));
                else rec.update(it.name(), it.max(), i);
            }
            items.flush();
            Map<String, Long> map = new HashMap<>();
            for (Check c : Rubric.CHECKS) {
                String track = c.track().name();
                RubricCheckRecord rec = checks.findByCodeAndTrack(c.code(), track).orElse(null);
                if (rec == null) rec = new RubricCheckRecord(c.item(), c.code(), track, c.method().name(), c.points(), c.label());
                else rec.update(c.item(), c.method().name(), c.points(), c.label());
                map.put(Rubric.key(c), checks.save(rec).getId());
            }
            return map;
        });
    }

    /** 체크 키(Rubric.key, 예: U1, D1-dev)의 check_id. 아직 맞추지 않았으면 먼저 맞춘다. */
    public synchronized long checkId(String key) {
        if (checkIds == null) syncRubric();
        Long id = checkIds.get(key);
        if (id == null) throw new IllegalArgumentException("알 수 없는 체크: " + key);
        return id;
    }

    /** 프롬프트 버전(파일)의 DB version_id. 없으면 넣는다. 이미 있는 행의 내용은 바꾸지 않는다. */
    public synchronized long versionId(PromptVersion v) {
        if (checkIds == null) syncRubric();
        return tx.execute(status -> {
            PromptVersionRecord rec = versions.findFirstByNameOrderByIdAsc(v.id()).orElse(null);
            if (rec != null && rec.isActive()) return rec.getId(); // 이미 활성이면 아무것도 쓰지 않는다
            if (rec == null) {
                Long parentId = v.parent() == null ? null
                        : versions.findFirstByNameOrderByIdAsc(v.parent()).map(PromptVersionRecord::getId).orElse(null);
                String note = v.note() == null ? null : v.note().length() > 500 ? v.note().substring(0, 500) : v.note();
                rec = versions.save(new PromptVersionRecord(v.id(), note, parentId,
                        v.judgeSystem() == null ? "" : v.judgeSystem(), false,
                        v.createdAt() == null ? Instant.now() : v.createdAt()));
                for (Check c : (List<Check>) v.apply(Rubric.CHECKS)) {
                    if (c.method() != Rubric.Method.JUDGE || c.judgeHint() == null || c.judgeHint().isBlank()) continue;
                    hints.save(new PromptCheckHint(rec.getId(), checkIds.get(Rubric.key(c)), c.judgeHint()));
                }
            }
            for (PromptVersionRecord other : versions.findByActiveTrue()) {
                if (!other.getId().equals(rec.getId())) other.setActive(false);
            }
            rec.setActive(true);
            return rec.getId();
        });
    }
}
