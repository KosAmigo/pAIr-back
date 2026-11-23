package com.pair.domain.task.repository;

import com.pair.domain.task.entity.Task;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByCode(String code);

    List<Task> findByCodeIn(Collection<String> codes);

    List<Task> findAllByOrderByIdAsc();

    /** 응시자에게 보이는 과제 목록용 (용도와 상태로 거른다) */
    List<Task> findByUsageAndStatusInOrderByIdAsc(Task.Usage usage, Collection<Task.Status> statuses);

    /** AI 추천이 겹침을 피하려고 보는 최근 과제 50개 (상태 무관) */
    List<Task> findTop50ByOrderByIdDesc();
}
