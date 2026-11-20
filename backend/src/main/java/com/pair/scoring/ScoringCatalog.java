package com.pair.scoring;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.grader.PromptVersion;
import com.pair.scoring.Rubric.Check;
import com.pair.scoring.db.PromptVersionRecord;
import com.pair.scoring.db.PromptVersionRecordRepository;
import com.pair.scoring.db.RubricCheckRecord;
import com.pair.scoring.db.RubricCheckRecordRepository;
import com.pair.scoring.db.RubricItemRecord;
import com.pair.scoring.db.RubricItemRecordRepository;

/**
 * 코드의 채점 기준(Rubric)을 DB 의 채점 기준 테이블에 맞춘다. 항목, 체크는 시작할 때 한 번 upsert 한다.
 * 프롬프트 버전은 PromptStore 가 DB 에 직접 저장하므로 여기서는 version_id 조회만 한다.
 */
@Component
public class ScoringCatalog {

    private final RubricItemRecordRepository items;
    private final RubricCheckRecordRepository checks;
    private final PromptVersionRecordRepository versions;
    private final TransactionTemplate tx;

    /** Rubric.key(check) → check_id */
    private Map<String, Long> checkIds;

    public ScoringCatalog(RubricItemRecordRepository items, RubricCheckRecordRepository checks,
                          PromptVersionRecordRepository versions,
                          PlatformTransactionManager txManager) {
        this.items = items;
        this.checks = checks;
        this.versions = versions;
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

    /** 프롬프트 버전의 DB version_id. 버전은 PromptStore 가 DB 에 저장하므로 name(버전 id)으로 행 id 만 찾는다. */
    public long versionId(PromptVersion v) {
        return versions.findFirstByNameOrderByIdAsc(v.id()).map(PromptVersionRecord::getId)
                .orElseThrow(() -> new IllegalStateException("DB 에 없는 프롬프트 버전입니다: " + v.id()));
    }
}
