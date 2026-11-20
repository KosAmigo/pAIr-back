package com.pair.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 과제 내용을 DB 에서 읽는지: 옛 폴더(task-fixtures)와 같은 결과, 실전 복사본 처리, 재시작 없이 새 과제 읽기 (H2 메모리 DB) */
@SpringBootTest
@ActiveProfiles("examtest")
class TaskRepositoryDbTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired TaskRepository tasks;
    @Autowired TaskCatalog catalog;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;

    @BeforeEach
    void seed() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
    }

    @Test
    void 옛_폴더와_같은_내용을_돌려준다() {
        for (String code : List.of(TestTasks.SELF_DEV, TestTasks.SELF_BIZ)) {
            assertThat(tasks.get(code)).isEqualTo(TestTasks.fixture(code));
            assertThat(tasks.visibleFiles(code)).isEqualTo(TestTasks.fixtureFiles(code, "files")).isNotEmpty();
            assertThat(tasks.hiddenFiles(code)).isEqualTo(TestTasks.fixtureFiles(code, "hidden")).isNotEmpty();
        }
        assertThat(tasks.visibleFiles(TestTasks.SELF_DEV).keySet()).noneMatch(k -> k.contains("Hidden"));
        assertThat(tasks.hiddenFiles(TestTasks.SELF_DEV)).containsKey("shop/HiddenOrderTest.java");
        assertThat(tasks.visibleFiles(TestTasks.SELF_BIZ)).containsKey("자료/요금제별_매출.csv");
        assertThat(tasks.get(TestTasks.SELF_BIZ).requiredHeadings()).containsExactly("## 결론", "## 근거", "## 리스크");
    }

    @Test
    void 목록은_원본_과제만_번호_순으로() {
        assertThat(tasks.all()).extracting(TaskDef::id).containsExactly(TestTasks.SELF_DEV, TestTasks.SELF_BIZ);
        assertThat(tasks.has(TestTasks.SELF_DEV)).isTrue();
        assertThat(tasks.has(TestTasks.LIVE_DEV)).isFalse();
        assertThat(tasks.has("no-such")).isFalse();
        assertThatThrownBy(() -> tasks.get(TestTasks.LIVE_DEV)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 실전_복사본은_원본_내용을_돌려준다() {
        assertThat(catalog.definition(TestTasks.LIVE_DEV)).isEqualTo(TestTasks.fixture(TestTasks.SELF_DEV));
        assertThat(catalog.definition(TestTasks.LIVE_BIZ)).isEqualTo(TestTasks.fixture(TestTasks.SELF_BIZ));
    }

    @Test
    void 새로_넣은_과제는_재시작_없이_읽힌다() {
        TaskRecord t = new TaskRecord(50L, "ai-made", "AI 과제", "DEV", 30, TaskRecord.Usage.SELF, TaskRecord.Status.HIDDEN, null, 1L, Instant.parse("2026-11-20T00:00:00Z"));
        t.setContent("설명 1줄\n설명 2줄", "요구 1\n요구 2", "{\"openFile\":\"a.txt\",\"editableFile\":null,\"requiredHeadings\":[],\"d4Minutes\":[10,20,30,40]}");
        taskRecords.save(t);
        taskFiles.save(new TaskFileRecord(50L, "a.txt", "안녕", "text/plain", "안녕".getBytes(StandardCharsets.UTF_8).length, TaskFileRecord.VISIBLE, false));
        taskFiles.save(new TaskFileRecord(50L, "t/hidden.txt", "정답", "text/plain", 6, TaskFileRecord.HIDDEN, true));
        // 바이너리(storage_key 만 있는 파일)는 건너뛴다
        taskFiles.save(new TaskFileRecord(50L, "doc.pdf", null, "application/pdf", 10, TaskFileRecord.VISIBLE, true));
        taskQuestions.save(new TaskQuestionRecord(50L, TaskQuestionRecord.FOLLOWUP, "ESSAY", 2, "둘째"));
        taskQuestions.save(new TaskQuestionRecord(50L, TaskQuestionRecord.FOLLOWUP, "ESSAY", 1, "첫째"));

        TaskDef d = tasks.get("ai-made");
        assertThat(d.type()).isEqualTo("dev");
        assertThat(d.minutes()).isEqualTo(30);
        assertThat(d.description()).containsExactly("설명 1줄", "설명 2줄");
        assertThat(d.requirements()).containsExactly("요구 1", "요구 2");
        assertThat(d.followUps()).containsExactly("첫째", "둘째");
        assertThat(d.openFile()).isEqualTo("a.txt");
        assertThat(d.d4Minutes()).containsExactly(10, 20, 30, 40);
        assertThat(tasks.visibleFiles("ai-made")).containsOnlyKeys("a.txt").containsEntry("a.txt", "안녕");
        assertThat(tasks.hiddenFiles("ai-made")).containsOnlyKeys("t/hidden.txt");
        assertThat(tasks.all()).extracting(TaskDef::id).contains("ai-made");
    }
}
