package com.pair.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRecordRepository extends JpaRepository<TaskRecord, Long> {

    Optional<TaskRecord> findByCode(String code);

    List<TaskRecord> findByCodeIn(Collection<String> codes);

    List<TaskRecord> findAllByOrderByIdAsc();

    /** 응시자에게 보이는 과제 목록용 (용도와 상태로 거른다) */
    List<TaskRecord> findByUsageAndStatusInOrderByIdAsc(TaskRecord.Usage usage, Collection<TaskRecord.Status> statuses);
}
