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

/** 문항 근거 문서 (ERD question_sources). pages 는 "1,2" 같은 쪽 번호 */
@Entity
@Table(name = "question_sources")
@IdClass(QuestionSourceRecord.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionSourceRecord {

    public record Key(Long questionId, Long taskFileId) implements Serializable {}

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Id
    @Column(name = "task_file_id")
    private Long taskFileId;

    @Column(length = 50)
    private String pages;

    @Column(columnDefinition = "text")
    private String note;

    public QuestionSourceRecord(Long questionId, Long taskFileId, String pages) {
        this.questionId = questionId;
        this.taskFileId = taskFileId;
        this.pages = pages;
    }
}
