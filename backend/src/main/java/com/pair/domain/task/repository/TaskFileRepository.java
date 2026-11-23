package com.pair.domain.task.repository;

import com.pair.domain.task.entity.TaskFile;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {

    List<TaskFile> findByTaskIdAndVisibilityOrderByPathAsc(Long taskId, String visibility);

    Optional<TaskFile> findByTaskIdAndPath(Long taskId, String path);

    /** 같은 경로의 파일을 모든 과제에서 (예: 목록에서 AI 생성 확인 결과를 한 번에 읽을 때) */
    List<TaskFile> findByPath(String path);
}
