package com.pair.taskgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pair.config.PairProperties;

/** 개발용 과제 파일 저장소: pilot-data/{key}. pair.taskgen.file-storage=local (기본값) */
@Component
@ConditionalOnProperty(name = "pair.taskgen.file-storage", havingValue = "local", matchIfMissing = true)
public class LocalTaskFileStorage implements TaskFileStorage {

    private final Path root;

    @Autowired
    public LocalTaskFileStorage(PairProperties pair) {
        this(Path.of(pair.dataDir()));
    }

    LocalTaskFileStorage(Path root) {
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

    private Path resolve(String key) {
        TaskFileStorage.requireValid(key);
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("잘못된 파일 키");
        return p;
    }
}
