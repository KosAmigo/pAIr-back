package com.pair.domain.scoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 역량 항목 (ERD rubric_items) */
@Entity
@Table(name = "rubric_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RubricItem {

    @Id
    @Column(name = "item_key", length = 30)
    private String key;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "max_score", nullable = false)
    private int maxScore;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public RubricItem(String key, String name, int maxScore, int sortOrder) {
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

}
