package com.pair.domain.task.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pair.domain.task.entity.Task;
import com.pair.global.error.ConflictException;
import com.pair.infra.storage.TaskFileStorage;

/**
 * 관리자 과제 삭제. 공개 중이거나, 응시 기록·회차·복사본이 있는 과제는 못 지운다(409).
 * 딸린 행은 한 트랜잭션에서 모두 지우고, 저장소의 PDF 는 커밋 뒤에 지운다(실패해도 삭제는 성공, 경고 로그만).
 * 생성 작업(task_gen_jobs)은 기록이라 행은 두고 task_code 만 비운다.
 */
@Service
public class TaskDeleteService {

    private static final Logger log = LoggerFactory.getLogger(TaskDeleteService.class);

    private static final String FILES_OF_TASK = "select task_file_id from task_files where task_id = ?";
    private static final String QUESTIONS_OF_TASK = "select question_id from task_questions where task_id = ?";

    private final TaskCatalogService catalog;
    private final JdbcTemplate jdbc;
    private final TaskFileStorage storage;

    public TaskDeleteService(TaskCatalogService catalog, JdbcTemplate jdbc, TaskFileStorage storage) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Transactional
    public void delete(String code) {
        Task t = catalog.require(code); // 없는 과제면 404
        long id = t.getId();
        if (t.getStatus() != Task.Status.HIDDEN) {
            throw new ConflictException("공개 중인 과제는 지울 수 없어요. 먼저 숨기세요");
        }
        if (count("select count(*) from attempts where task_id = ?", id) > 0) {
            throw new ConflictException("응시 기록이 있는 과제는 지울 수 없어요.");
        }
        if (count("select count(*) from exam_session_tasks where task_id = ?", id) > 0) {
            throw new ConflictException("회차에 들어 있는 과제는 지울 수 없어요.");
        }
        if (count("select count(*) from tasks where parent_task_id = ?", id) > 0) {
            throw new ConflictException("이 과제를 원본으로 만든 복사본이 있어서 지울 수 없어요.");
        }

        List<String> keys = jdbc.queryForList("select storage_key from task_files where task_id = ? and storage_key is not null", String.class, id);

        jdbc.update("delete from question_choices where question_id in (" + QUESTIONS_OF_TASK + ")", id);
        jdbc.update("delete from short_answer_keys where question_id in (" + QUESTIONS_OF_TASK + ")", id);
        jdbc.update("delete from question_sources where question_id in (" + QUESTIONS_OF_TASK + ") or task_file_id in (" + FILES_OF_TASK + ")", id, id);
        // document_chunks 는 운영(PostgreSQL pgvector)에만 있고 엔티티가 없어 테스트 DB(H2)에는 없다
        if (count("select count(*) from information_schema.tables where lower(table_name) = 'document_chunks'") > 0) {
            jdbc.update("delete from document_chunks where task_file_id in (" + FILES_OF_TASK + ")", id);
        }
        jdbc.update("delete from document_pages where task_file_id in (" + FILES_OF_TASK + ")", id);
        jdbc.update("delete from task_files where task_id = ?", id);
        jdbc.update("delete from task_questions where task_id = ?", id);
        jdbc.update("delete from task_issue_reports where task_id = ?", id);
        jdbc.update("update task_gen_jobs set task_code = null where task_code = ?", t.getCode());
        jdbc.update("delete from tasks where task_id = ?", id);

        afterCommit(() -> {
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    log.warn("과제 {} 삭제 뒤 저장소 파일 {} 을 지우지 못했습니다: {}", code, key, e.toString());
                }
            }
        });
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static void afterCommit(Runnable r) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            r.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                r.run();
            }
        });
    }
}
