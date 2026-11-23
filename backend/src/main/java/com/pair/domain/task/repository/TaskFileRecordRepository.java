package com.pair.domain.task.repository;

import com.pair.domain.task.entity.TaskFileRecord;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRecordRepository extends JpaRepository<TaskFileRecord, Long> {

    List<TaskFileRecord> findByTaskIdAndVisibilityOrderByPathAsc(Long taskId, String visibility);

    Optional<TaskFileRecord> findByTaskIdAndPath(Long taskId, String path);

    /** 같은 경로의 파일을 모든 과제에서 (예: 목록에서 AI 생성 확인 결과를 한 번에 읽을 때) */
    List<TaskFileRecord> findByPath(String path);
}
