package com.pair.taskconsole;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskPublicationRepository extends JpaRepository<TaskPublication, String> {

    List<TaskPublication> findByTaskIdIn(Collection<String> taskIds);
}
