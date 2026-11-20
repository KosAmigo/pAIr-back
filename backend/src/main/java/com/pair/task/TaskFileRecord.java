package com.pair.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 과제 파일 (ERD task_files). 텍스트 파일은 content 에, PDF 같은 바이너리는 storage_key 에 둔다 */
@Entity
@Table(name = "task_files", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "path"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskFileRecord {

    public static final String VISIBLE = "VISIBLE";
    public static final String HIDDEN = "HIDDEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_file_id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** VISIBLE | HIDDEN */
    @Column(nullable = false, length = 10)
    private String visibility;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly;

    /** 테스트·시드용 텍스트 파일 */
    public TaskFileRecord(Long taskId, String path, String content, String mimeType, long sizeBytes, String visibility,
            boolean readOnly) {
        this.taskId = taskId;
        this.path = path;
        this.content = content;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.visibility = visibility;
        this.readOnly = readOnly;
    }
}
