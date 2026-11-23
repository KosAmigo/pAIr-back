package com.pair.infra.storage;

import java.net.URI;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 과제 PDF 를 이 PC 에만 저장(local)하는데 DB 는 공용(원격)이면, DB 행은 모두가 보지만 파일은 이 PC 에만 남아
 * 다른 사람·배포 서버에서 404 가 난다. 비개발(BIZ) 과제는 PDF 를 만들므로 이 조합이면 AI 를 부르기 전에 막는다.
 * 개발(DEV) 과제는 PDF 가 없어서 막지 않는다.
 */
@Component
public class TaskFileStorageGuard {

    private static final Logger log = LoggerFactory.getLogger(TaskFileStorageGuard.class);

    private final String storage;
    private final String dbUrl;

    public TaskFileStorageGuard(
            @Value("${pair.taskgen.file-storage:local}") String storage,
            @Value("${spring.datasource.url:}") String dbUrl) {
        this.storage = storage == null ? "local" : storage.trim().toLowerCase(Locale.ROOT);
        this.dbUrl = dbUrl == null ? "" : dbUrl.trim();
    }

    /** BIZ 생성을 시작하기 전에 부른다. 위험한 조합이면 알기 쉬운 오류(400) */
    public void requireSafe(String track) {
        if (!"BIZ".equals(track)) return;
        if (isRisky()) throw new IllegalStateException(MESSAGE);
    }

    static final String MESSAGE = "공용 DB에 연결돼 있어 PDF를 이 PC에만 저장할 수 없습니다. PAIR_TASKFILE_STORAGE=s3로 설정하세요.";

    boolean isRisky() {
        return "local".equals(storage) && !isLocalDb(dbUrl);
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnAtStartup() {
        if (isRisky()) {
            log.warn("과제 PDF 저장소가 local 인데 DB 는 공용({})입니다. 비개발 과제 생성은 막힙니다. PAIR_TASKFILE_STORAGE=s3 로 설정하세요.", hostOf(dbUrl));
        }
    }

    /** H2 이거나 호스트가 localhost / 127.0.0.1 (::1 포함)이면 로컬 DB */
    static boolean isLocalDb(String url) {
        if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) return true;
        String host = hostOf(url);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }

    private static String hostOf(String url) {
        try {
            // jdbc:postgresql://host:5432/db → host
            String s = url.startsWith("jdbc:") ? url.substring(5) : url;
            String h = URI.create(s).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
