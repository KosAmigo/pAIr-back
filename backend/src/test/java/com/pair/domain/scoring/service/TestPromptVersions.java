package com.pair.domain.scoring.service;

import java.time.Instant;

import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.scoring.entity.PromptVersion;
import com.pair.domain.scoring.repository.PromptVersionRepository;

/** 채점 테스트용: 프롬프트 버전 행을 DB 에 미리 넣어 둔다(채점은 DB 에 있는 버전만 쓴다). 이미 있으면 그대로 둔다. */
public final class TestPromptVersions {

    private TestPromptVersions() {}

    public static long ensure(PromptVersionRepository repo, PromptVersionStateDto v) {
        return repo.findFirstByNameOrderByIdAsc(v.id()).map(PromptVersion::getId).orElseGet(() ->
                repo.save(new PromptVersion(v.id(), v.name(), v.note(), null, v.judgeSystem(), false,
                        v.createdAt() == null ? Instant.now() : v.createdAt())).getId());
    }
}
