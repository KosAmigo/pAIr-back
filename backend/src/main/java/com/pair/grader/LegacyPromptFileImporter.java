package com.pair.grader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.config.PairProperties;

/**
 * 옛 파일 가져오기용, 다음 정리 때 삭제.
 * 프롬프트 버전 원본이 파일(grader/versions/*.json, grader/active.txt)이던 때의 데이터를 DB 로 한 번 옮긴다.
 * - DB 에 같은 name(버전 id)이 없는 파일만 넣는다. 몇 번 돌려도 결과가 같다.
 * - DB 에 활성 버전이 아직 없을 때만 active.txt 가 가리키는 버전을 활성으로 한다.
 * 팀원 로컬 DB 에도 들어가야 하므로 파일은 지우지 않는다. 모두 옮긴 뒤 이 클래스와 파일을 함께 정리한다.
 */
@Component
public class LegacyPromptFileImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyPromptFileImporter.class);

    private final PromptStore store;
    private final ObjectMapper json;
    private final Path dir;
    private final Path activeFile;

    public LegacyPromptFileImporter(PromptStore store, ObjectMapper json, PairProperties props) {
        this.store = store;
        this.json = json;
        this.dir = Path.of(props.grader().dir(), "versions").toAbsolutePath();
        this.activeFile = Path.of(props.grader().dir(), "active.txt").toAbsolutePath();
    }

    /** PromptStore 의 기본값 채우기보다 먼저 돈다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void onReady() {
        importFiles();
    }

    /** 가져온 버전 수를 돌려준다. */
    public synchronized int importFiles() {
        int added = 0;
        if (Files.isDirectory(dir)) {
            List<PromptVersion> found = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                    try {
                        PromptVersion v = json.readValue(p.toFile(), PromptVersion.class);
                        if (v.id() != null && !v.id().isBlank()) found.add(v);
                    } catch (IOException | RuntimeException e) {
                        log.warn("프롬프트 버전 파일을 읽지 못했습니다: {} ({})", p.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("프롬프트 버전 폴더를 읽지 못했습니다: {}", e.getMessage());
            }
            // 바탕 버전이 먼저 들어가야 parent 가 이어지므로 생성 시각 순으로
            found.sort(Comparator.comparing(PromptVersion::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (PromptVersion v : found) {
                if (store.exists(v.id())) continue;
                store.insert(v);
                added++;
            }
        }
        if (!store.hasActive()) {
            try {
                if (Files.exists(activeFile)) {
                    String id = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
                    if (!id.isEmpty() && store.exists(id)) store.setActive(id);
                }
            } catch (IOException e) {
                log.warn("active.txt 를 읽지 못했습니다: {}", e.getMessage());
            }
        }
        if (added > 0) log.info("옛 프롬프트 버전 파일 {}개를 DB 로 가져왔습니다.", added);
        return added;
    }
}
