package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentPageRecordRepository extends JpaRepository<DocumentPageRecord, DocumentPageRecord.Key> {

    List<DocumentPageRecord> findByTaskFileIdOrderByPageNoAsc(Long taskFileId);
}
