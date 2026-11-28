package com.pair.domain.taskissue.repository;

import com.pair.domain.taskissue.entity.TaskIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskIssueRepository extends JpaRepository<TaskIssue, Long> {
    @EntityGraph(attributePaths = { "task", "submittedBy" })
    @Query(value = """
            select i from TaskIssue i
            where (:taskCode is null or i.task.code = :taskCode)
              and (:category is null or i.category = :category)
              and (:status is null or i.status = :status)
            """,
            countQuery = """
            select count(i) from TaskIssue i
            where (:taskCode is null or i.task.code = :taskCode)
              and (:category is null or i.category = :category)
              and (:status is null or i.status = :status)
            """)
    Page<TaskIssue> search(@Param("taskCode") String taskCode, @Param("category") TaskIssue.Category category,
            @Param("status") TaskIssue.Status status, Pageable pageable);
}
