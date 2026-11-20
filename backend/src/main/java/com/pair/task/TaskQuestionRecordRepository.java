package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskQuestionRecordRepository extends JpaRepository<TaskQuestionRecord, Long> {

    List<TaskQuestionRecord> findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(Long taskId, String stage);

    List<TaskQuestionRecord> findByTaskIdAndStageOrderBySeqAsc(Long taskId, String stage);
}
