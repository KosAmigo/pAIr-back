package com.pair.domain.scoring.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 판정 기준 문장 (ERD prompt_check_hints) */
@Entity
@Table(name = "prompt_check_hints")
@IdClass(PromptCheckHint.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PromptCheckHint {

    public static class Key implements Serializable {
        private Long versionId;
        private Long checkId;

        public Key() {}

        public Key(Long versionId, Long checkId) {
            this.versionId = versionId;
            this.checkId = checkId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(versionId, k.versionId) && Objects.equals(checkId, k.checkId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionId, checkId);
        }
    }

    @Id
    @Column(name = "version_id")
    private Long versionId;

    @Id
    @Column(name = "check_id")
    private Long checkId;

    @Column(nullable = false, columnDefinition = "text")
    private String hint;

    public PromptCheckHint(Long versionId, Long checkId, String hint) {
        this.versionId = versionId;
        this.checkId = checkId;
        this.hint = hint;
    }

}
