package com.pair.infra.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pair.global.config.PairProperties;

/**
 * 개발용 파일 저장소: pilot-data/{key} (예: pilot-data/exam-snapshots/{회차}/{이벤트}.jpg)
 * pair.exam.snapshot-storage=local (기본값). 파일 서버 구현은 다른 값(예: s3)으로 켠다.
 */
@Component
@ConditionalOnProperty(name = "pair.exam.snapshot-storage", havingValue = "local", matchIfMissing = true)
public class LocalSnapshotStorage implements SnapshotStorage {

    private final Path root;

    @Autowired
    public LocalSnapshotStorage(PairProperties pair) {
        this(Path.of(pair.dataDir()));
    }

    LocalSnapshotStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패", e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path p = resolve(key);
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 삭제 실패", e);
        }
    }

    /** 키는 서버가 만든 숫자뿐이지만, 경로 조작을 한 번 더 막는다 */
    private Path resolve(String key) {
        SnapshotStorage.requireValid(key);
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("잘못된 파일 키");
        return p;
    }
}
