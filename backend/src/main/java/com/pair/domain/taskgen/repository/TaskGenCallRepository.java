package com.pair.domain.taskgen.repository;

import com.pair.domain.taskgen.entity.TaskGenCall;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskGenCallRepository extends JpaRepository<TaskGenCall, String> {

    Optional<TaskGenCall> findByJobIdAndInputHashAndNth(String jobId, String inputHash, int nth);

    List<TaskGenCall> findByJobId(String jobId);
}
