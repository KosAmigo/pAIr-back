package com.pair.domain.task.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문서(PDF) 쪽별 글 (ERD document_pages). AI 가 쓴 글을 그대로 넣는다 */
@Entity
@Table(name = "document_pages")
@IdClass(DocumentPageRecord.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentPageRecord {

    public record Key(Long taskFileId, Integer pageNo) implements Serializable {}

    @Id
    @Column(name = "task_file_id")
    private Long taskFileId;

    @Id
    @Column(name = "page_no")
    private Integer pageNo;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    public DocumentPageRecord(Long taskFileId, int pageNo, String text) {
        this.taskFileId = taskFileId;
        this.pageNo = pageNo;
        this.text = text;
    }
}
