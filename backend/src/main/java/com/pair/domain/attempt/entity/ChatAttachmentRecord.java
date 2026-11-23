package com.pair.domain.attempt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 질문 턴에 붙인 첨부 (ERD chat_attachments). sentText 는 그때 AI 에게 실제로 보낸 글자 */
@Entity
@Table(name = "chat_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatAttachmentRecord {

    public static final String ATTEMPT_FILE = "ATTEMPT_FILE";
    public static final String TASK_FILE = "TASK_FILE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attachment_id")
    private Long id;

    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "task_file_id")
    private Long taskFileId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(length = 50)
    private String pages;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "sent_text", nullable = false, columnDefinition = "text")
    private String sentText;

    public ChatAttachmentRecord(Long turnId, String sourceType, Long taskFileId, String fileName, String pages,
                                String mimeType, String sentText) {
        this.turnId = turnId;
        this.sourceType = sourceType;
        this.taskFileId = taskFileId;
        this.fileName = fileName;
        this.pages = pages;
        this.mimeType = mimeType;
        this.sentText = sentText;
    }
}
