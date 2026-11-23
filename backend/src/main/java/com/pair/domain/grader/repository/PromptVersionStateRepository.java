package com.pair.domain.grader.repository;

import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.grader.service.LegacyPromptImportService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.scoring.service.RubricService;
import com.pair.domain.scoring.service.RubricService.Check;
import com.pair.domain.scoring.service.ScoringCatalogService;
import com.pair.domain.scoring.service.ScoringPromptService;
import com.pair.domain.scoring.entity.PromptCheckHint;
import com.pair.domain.scoring.repository.PromptCheckHintRepository;
import com.pair.domain.scoring.entity.PromptVersion;
import com.pair.domain.scoring.repository.PromptVersionRepository;

/**
 * 채점 프롬프트 버전 저장소. 원본은 DB(prompt_versions, prompt_check_hints)다.
 * - 버전 한 개 = prompt_versions 한 행. 한 번 저장한 버전은 고치지 않고 새 버전으로 저장한다.
 * - 실제 채점에 쓸 버전 = is_active 인 행 하나(활성 변경은 한 트랜잭션에서 기존 활성 끄기 → 새로 켜기).
 * - 옛 grader/versions 파일은 LegacyPromptImportService 가 시작할 때 DB 로 한 번 옮긴다.
 */
@Component
public class PromptVersionStateRepository {

    /** 2026-11-12: 6개 역량(재현성 제외, 문제 분해력·위임 판단력 추가) 기준의 기본 버전. v1_기본은 예전 5개 항목 기준. */
    public static final String DEFAULT_ID = "v2_기본";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Asia/Seoul"));
    private static final int TITLE_MAX = 100;

    private final PromptVersionRepository versions;
    private final PromptCheckHintRepository hints;
    private final ScoringCatalogService catalog;
    private final TransactionTemplate tx;

    public PromptVersionStateRepository(PromptVersionRepository versions, PromptCheckHintRepository hints,
                       ScoringCatalogService catalog, PlatformTransactionManager txManager) {
        this.versions = versions;
        this.hints = hints;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 코드 기본값으로 만든 버전 (v2_기본). */
    public static PromptVersionStateDto builtin() {
        Map<String, PromptVersionStateDto.CheckPrompt> checks = new LinkedHashMap<>();
        for (RubricService.Check c : RubricService.CHECKS) checks.put(RubricService.key(c), new PromptVersionStateDto.CheckPrompt(c.judgeHint(), c.points()));
        return new PromptVersionStateDto(DEFAULT_ID, "기본 (6개 역량)", "재현성을 빼고 문제 분해력, 위임 판단력을 넣은 기준의 코드 기본값",
                Instant.parse("2026-11-12T09:00:00Z"), null, ScoringPromptService.JUDGE_SYSTEM, checks);
    }

    /**
     * 시작할 때: DB 에 버전이 하나도 없으면 내장 기본 버전을 넣고, 활성 버전이 없으면 기본 버전(없으면 가장 오래된 버전)을 활성으로 한다.
     * 옛 파일 가져오기(LegacyPromptImportService)가 먼저 돈 뒤에 실행된다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureDefaults();
    }

    public synchronized void ensureDefaults() {
        Map<String, Long> ids = checkIds();
        tx.executeWithoutResult(s -> {
            if (versions.count() == 0) insertRow(builtin(), ids);
            if (versions.findByActiveTrue().isEmpty()) {
                PromptVersion target = versions.findFirstByNameOrderByIdAsc(DEFAULT_ID)
                        .orElseGet(() -> versions.findAllByOrderByCreatedAtAscIdAsc().stream().findFirst().orElse(null));
                if (target != null) target.setActive(true);
            }
        });
    }

    public List<PromptVersionStateDto> list() {
        List<PromptVersion> recs = versions.findAllByOrderByCreatedAtAscIdAsc();
        Map<Long, String> names = new HashMap<>();
        for (PromptVersion r : recs) names.put(r.getId(), r.getName());
        Map<Long, String> keyById = keyById();
        Map<Long, Map<Long, String>> hintsByVersion = new HashMap<>();
        for (PromptCheckHint h : hints.findAll()) {
            hintsByVersion.computeIfAbsent(h.getVersionId(), k -> new HashMap<>()).put(h.getCheckId(), h.getHint());
        }
        List<PromptVersionStateDto> out = new ArrayList<>();
        for (PromptVersion r : recs) {
            out.add(toVersion(r, names.get(r.getParentVersionId()), hintsByVersion.getOrDefault(r.getId(), Map.of()), keyById));
        }
        return out;
    }

    public PromptVersionStateDto get(String id) {
        PromptVersion r = versions.findFirstByNameOrderByIdAsc(id == null ? "" : id)
                .orElseThrow(() -> new NoSuchElementException("없는 프롬프트 버전입니다: " + id));
        return toVersion(r);
    }

    /** 새 버전 저장. id 는 "날짜-시각_이름". 같은 이름이 이미 있으면 뒤에 -2, -3 을 붙인다. */
    public synchronized PromptVersionStateDto create(PromptVersionStateDto draft) {
        String name = clean(draft.name());
        if (name.isEmpty()) throw new IllegalArgumentException("버전 이름을 입력하세요.");
        if (draft.judgeSystem() == null || draft.judgeSystem().isBlank()) throw new IllegalArgumentException("공통 채점 규칙이 비어 있습니다.");
        Instant now = Instant.now();
        String base = STAMP.format(now) + "_" + name;
        String id = base;
        for (int i = 2; versions.existsByName(id); i++) id = base + "-" + i;
        Map<String, PromptVersionStateDto.CheckPrompt> checks = new LinkedHashMap<>();
        for (RubricService.Check c : RubricService.CHECKS) {
            String k = RubricService.key(c);
            PromptVersionStateDto.CheckPrompt p = draft.checks() == null ? null : draft.checks().get(k);
            int points = c.points(); // 배점은 고정: 보낸 값과 관계없이 기준표 배점을 저장
            String hint = c.method() == RubricService.Method.JUDGE ? (p == null || p.hint() == null || p.hint().isBlank() ? c.judgeHint() : p.hint().trim()) : null;
            checks.put(k, new PromptVersionStateDto.CheckPrompt(hint, points));
        }
        String title = draft.name().trim();
        PromptVersionStateDto v = new PromptVersionStateDto(id, title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title,
                draft.note() == null ? "" : draft.note().trim(), now, draft.parent(), draft.judgeSystem().trim(), checks);
        insert(v);
        return get(id);
    }

    /** 활성 버전의 id(name). 활성 행이 없으면 기본 버전 id. */
    public String activeId() {
        List<PromptVersion> active = versions.findByActiveTrue();
        return active.isEmpty() ? DEFAULT_ID : active.get(0).getName();
    }

    /** 실제 응시 채점에 쓸 버전. */
    public PromptVersionStateDto active() {
        List<PromptVersion> active = versions.findByActiveTrue();
        if (!active.isEmpty()) return toVersion(active.get(0));
        try {
            return get(DEFAULT_ID);
        } catch (NoSuchElementException e) {
            return builtin();
        }
    }

    /** 활성 버전 변경: 한 트랜잭션에서 기존 활성을 끄고(먼저 반영) 새로 켠다. 활성은 항상 하나. */
    public synchronized void setActive(String id) {
        tx.executeWithoutResult(s -> {
            PromptVersion rec = versions.findFirstByNameOrderByIdAsc(id == null ? "" : id)
                    .orElseThrow(() -> new NoSuchElementException("없는 프롬프트 버전입니다: " + id));
            for (PromptVersion other : versions.findByActiveTrue()) {
                if (!other.getId().equals(rec.getId())) other.setActive(false);
            }
            versions.flush(); // 부분 유니크 인덱스(활성 하나)에 걸리지 않게 끄기를 먼저 반영
            rec.setActive(true);
        });
    }

    /** 이미 있는 버전 id 인지. */
    public boolean exists(String id) {
        return versions.existsByName(id);
    }

    public boolean hasActive() {
        return !versions.findByActiveTrue().isEmpty();
    }

    /** 옛 파일 가져오기용: id, 생성 시각을 그대로 두고 DB 에 넣는다. */
    public synchronized void insert(PromptVersionStateDto v) {
        Map<String, Long> ids = checkIds();
        tx.executeWithoutResult(s -> insertRow(v, ids));
    }

    // ───────── 내부 ─────────

    /** 트랜잭션 안에서 버전 행과 판정 기준 행을 넣는다. 활성은 건드리지 않는다. */
    private void insertRow(PromptVersionStateDto v, Map<String, Long> checkIds) {
        Long parentId = v.parent() == null ? null
                : versions.findFirstByNameOrderByIdAsc(v.parent()).map(PromptVersion::getId).orElse(null);
        String title = v.name() == null || v.name().isBlank() ? null
                : v.name().length() > TITLE_MAX ? v.name().substring(0, TITLE_MAX) : v.name();
        PromptVersion rec = versions.save(new PromptVersion(v.id(), title, v.note(), parentId,
                v.judgeSystem() == null ? "" : v.judgeSystem(), false, v.createdAt() == null ? Instant.now() : v.createdAt()));
        for (Check c : v.apply(RubricService.CHECKS)) {
            if (c.method() != RubricService.Method.JUDGE || c.judgeHint() == null || c.judgeHint().isBlank()) continue;
            hints.save(new PromptCheckHint(rec.getId(), checkIds.get(RubricService.key(c)), c.judgeHint()));
        }
    }

    private PromptVersionStateDto toVersion(PromptVersion r) {
        String parent = r.getParentVersionId() == null ? null
                : versions.findById(r.getParentVersionId()).map(PromptVersion::getName).orElse(null);
        Map<Long, String> hintByCheck = new HashMap<>();
        for (PromptCheckHint h : hints.findByVersionId(r.getId())) hintByCheck.put(h.getCheckId(), h.getHint());
        return toVersion(r, parent, hintByCheck, keyById());
    }

    /** DB 행 → 객체. 체크는 기준표(RubricService) 전체를 키 순서대로 채우고, 판정 기준은 행에서, 배점은 기준표에서 가져온다. */
    private static PromptVersionStateDto toVersion(PromptVersion r, String parentName, Map<Long, String> hintByCheck,
                                           Map<Long, String> keyById) {
        Map<String, String> hintByKey = new HashMap<>();
        hintByCheck.forEach((checkId, hint) -> {
            String key = keyById.get(checkId);
            if (key != null) hintByKey.put(key, hint);
        });
        Map<String, PromptVersionStateDto.CheckPrompt> checks = new LinkedHashMap<>();
        for (Check c : RubricService.CHECKS) {
            String k = RubricService.key(c);
            checks.put(k, new PromptVersionStateDto.CheckPrompt(c.method() == RubricService.Method.JUDGE ? hintByKey.get(k) : null, c.points()));
        }
        String title = r.getTitle() == null || r.getTitle().isBlank() ? r.getName() : r.getTitle();
        return new PromptVersionStateDto(r.getName(), title, r.getNote(), r.getCreatedAt(), parentName, r.getJudgeSystem(), checks);
    }

    /** RubricService.key → check_id (기준표가 DB 에 맞춰져 있지 않으면 먼저 맞춘다). 트랜잭션 밖에서 부른다. */
    private Map<String, Long> checkIds() {
        Map<String, Long> ids = new HashMap<>();
        for (Check c : RubricService.CHECKS) ids.put(RubricService.key(c), catalog.checkId(RubricService.key(c)));
        return ids;
    }

    private Map<Long, String> keyById() {
        Map<Long, String> out = new HashMap<>();
        checkIds().forEach((k, id) -> out.put(id, k));
        return out;
    }

    /** 버전 id 에 쓸 수 있게 한글, 영문, 숫자, -, _ 만 남기고 공백은 - 로. */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", "-").replaceAll("[^0-9A-Za-z가-힣_\\-]", "");
        return t.length() > 30 ? t.substring(0, 30) : t;
    }
}
