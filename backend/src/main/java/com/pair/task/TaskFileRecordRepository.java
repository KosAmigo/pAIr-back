package com.pair.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRecordRepository extends JpaRepository<TaskFileRecord, Long> {

    List<TaskFileRecord> findByTaskIdAndVisibilityOrderByPathAsc(Long taskId, String visibility);

    Optional<TaskFileRecord> findByTaskIdAndPath(Long taskId, String path);
}
