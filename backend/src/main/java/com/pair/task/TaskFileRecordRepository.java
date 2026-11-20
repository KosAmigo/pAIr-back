package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRecordRepository extends JpaRepository<TaskFileRecord, Long> {

    List<TaskFileRecord> findByTaskIdAndVisibilityOrderByPathAsc(Long taskId, String visibility);
}
