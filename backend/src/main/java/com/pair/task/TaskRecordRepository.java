package com.pair.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRecordRepository extends JpaRepository<TaskRecord, Long> {

    Optional<TaskRecord> findByCode(String code);

    List<TaskRecord> findByCodeIn(Collection<String> codes);

    List<TaskRecord> findAllByOrderByIdAsc();
}
