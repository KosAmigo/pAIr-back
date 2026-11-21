package com.pair.attempt.db;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatAttachmentRecordRepository extends JpaRepository<ChatAttachmentRecord, Long> {

    List<ChatAttachmentRecord> findByTurnIdInOrderByIdAsc(Collection<Long> turnIds);

    /** 화면에 보일 첨부 요약. 원문(sent_text)은 읽지 않고 길이만 받는다 (원격 DB 에서는 읽는 글자가 곧 전송량이다) */
    interface Summary {
        Integer getTurnNo();

        Long getId();

        String getSourceType();

        String getFileName();

        String getPages();

        Integer getChars();
    }

    @Query("select t.turnNo as turnNo, a.id as id, a.sourceType as sourceType, a.fileName as fileName, a.pages as pages, "
            + "length(a.sentText) as chars from ChatAttachmentRecord a, ChatTurnRecord t "
            + "where a.turnId = t.id and t.attemptId = :attemptId order by a.id")
    List<Summary> summariesByAttempt(@Param("attemptId") Long attemptId);
}
