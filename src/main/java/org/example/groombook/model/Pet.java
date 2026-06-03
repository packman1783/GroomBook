package org.example.groombook.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.example.groombook.model.enums.PetDifficulty;
import org.example.groombook.model.enums.PetType;

import java.time.LocalDateTime;

@Entity
@Table(name = "pets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PetType type;

    @Column(name = "breed", length = 100)
    private String breed;

    /** Сложность работы — выставляет мастер после первого визита */
    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    @Builder.Default
    private PetDifficulty difficulty = PetDifficulty.EASY;

    /** Заметка о сложности — видит только мастер.
     *  Пример: "Кусается при стрижке лап, агрессивен" */
    @Column(name = "difficulty_note", columnDefinition = "TEXT")
    private String difficultyNote;

    /** Soft-delete: деактивированный питомец не исчезает из истории */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Доменная логика ────────────────────────────────────────────────────────

    public boolean isRefused() {
        return difficulty == PetDifficulty.REFUSED;
    }

    public void updateDifficulty(PetDifficulty difficulty, String note) {
        this.difficulty = difficulty;
        this.difficultyNote = note;
    }

    public void deactivate() {
        this.active = false;
    }
}
