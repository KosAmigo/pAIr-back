package com.pair.domain.task.repository;

import com.pair.domain.task.entity.DocumentPage;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, DocumentPage.Key> {

    List<DocumentPage> findByTaskFileIdOrderByPageNoAsc(Long taskFileId);

    /** 파일별 쪽 수 (목록에서 파일마다 세지 않도록 한 번에) */
    @org.springframework.data.jpa.repository.Query("select p.taskFileId, count(p) from DocumentPage p where p.taskFileId in :ids group by p.taskFileId")
    List<Object[]> countByFiles(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
