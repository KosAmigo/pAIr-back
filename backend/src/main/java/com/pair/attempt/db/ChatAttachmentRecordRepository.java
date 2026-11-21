package com.pair.attempt.db;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAttachmentRecordRepository extends JpaRepository<ChatAttachmentRecord, Long> {

    List<ChatAttachmentRecord> findByTurnIdInOrderByIdAsc(Collection<Long> turnIds);
}
