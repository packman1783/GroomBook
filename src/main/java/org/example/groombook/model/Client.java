package org.example.groombook.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.example.groombook.enums.ClientStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Уникальный ID пользователя в Telegram */
    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Основной идентификатор клиента для связи */
    @Column(name = "phone", nullable = false, unique = true, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ClientStatus status = ClientStatus.ACTIVE;

    /** Причина смены статуса — видит только мастер */
    @Column(name = "status_reason", columnDefinition = "TEXT")
    private String statusReason;

    /** Счётчик случаев когда клиент не пришёл без предупреждения */
    @Column(name = "no_show_count", nullable = false)
    @Builder.Default
    private int noShowCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Pet> pets = new ArrayList<>();

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Booking> bookings = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Доменная логика ────────────────────────────────────────────────────────

    public boolean isActive() {
        return status == ClientStatus.ACTIVE;
    }

    public boolean isBlocked() {
        return status == ClientStatus.BLOCKED;
    }

    public boolean requiresApproval() {
        return status == ClientStatus.REQUIRES_APPROVAL;
    }

    public void incrementNoShowCount() {
        this.noShowCount++;
    }

    public void changeStatus(ClientStatus newStatus, String reason) {
        this.status = newStatus;
        this.statusReason = reason;
    }
}
