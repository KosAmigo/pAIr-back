package com.pair.domain.task.repository;

import com.pair.domain.task.entity.TaskQuestion;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskQuestionRepository extends JpaRepository<TaskQuestion, Long> {

    List<TaskQuestion> findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(Long taskId, String stage);

    List<TaskQuestion> findByTaskIdAndStageOrderBySeqAsc(Long taskId, String stage);
}
