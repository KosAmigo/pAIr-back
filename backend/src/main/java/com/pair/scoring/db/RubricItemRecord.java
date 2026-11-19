package com.pair.scoring.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 역량 항목 (ERD rubric_items) */
@Entity
@Table(name = "rubric_items")
public class RubricItemRecord {

    @Id
    @Column(name = "item_key", length = 30)
    private String key;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "max_score", nullable = false)
    private int maxScore;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected RubricItemRecord() {}

    public RubricItemRecord(String key, String name, int maxScore, int sortOrder) {
        this.key = key;
        this.name = name;
        this.maxScore = maxScore;
        this.sortOrder = sortOrder;
    }

    public void update(String name, int maxScore, int sortOrder) {
        this.name = name;
        this.maxScore = maxScore;
        this.sortOrder = sortOrder;
    }

    public String getKey() { return key; }
    public String getName() { return name; }
    public int getMaxScore() { return maxScore; }
    public int getSortOrder() { return sortOrder; }
}
