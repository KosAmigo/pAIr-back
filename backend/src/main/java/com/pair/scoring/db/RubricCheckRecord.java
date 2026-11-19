package com.pair.scoring.db;

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

/** 체크 항목 (ERD rubric_checks). track: ALL/DEV/BIZ, method: AUTO/JUDGE */
@Entity
@Table(name = "rubric_checks", uniqueConstraints = @UniqueConstraint(columnNames = {"code", "track"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RubricCheckRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "check_id")
    private Long id;

    @Column(name = "item_key", nullable = false, length = 30)
    private String itemKey;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 10)
    private String track;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false)
    private int points;

    @Column(nullable = false, length = 300)
    private String label;

    public RubricCheckRecord(String itemKey, String code, String track, String method, int points, String label) {
        this.itemKey = itemKey;
        this.code = code;
        this.track = track;
        this.method = method;
        this.points = points;
        this.label = label;
    }

    public void update(String itemKey, String method, int points, String label) {
        this.itemKey = itemKey;
        this.method = method;
        this.points = points;
        this.label = label;
    }

}
