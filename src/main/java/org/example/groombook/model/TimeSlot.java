package org.example.groombook.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.example.groombook.enums.SlotStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "time_slots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"date", "start_time"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimeSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SlotStatus status = SlotStatus.FREE;

    /** Причина блокировки — видит только мастер.
     *  Пример: "Стоматолог", "Личные дела" */
    @Column(name = "block_reason", length = 200)
    private String blockReason;

    /** true = создан мастером вручную вне шаблона (договорная запись или внеплановый слот) */
    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean manual = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Доменная логика ────────────────────────────────────────────────────────

    public boolean isFree() {
        return status == SlotStatus.FREE;
    }

    public boolean isBooked() {
        return status == SlotStatus.BOOKED;
    }

    /** Слот доступен для бронирования клиентом */
    public boolean isAvailableForBooking() {
        return status == SlotStatus.FREE;
    }

    /** Начало слота в виде LocalDateTime — удобно для сравнений с текущим временем */
    public LocalDateTime getStartDateTime() {
        return LocalDateTime.of(date, startTime);
    }

    /** Слот можно отменить — до его начала ещё больше N часов */
    public boolean isCancellableByClient(int hoursBeforeStart) {
        return LocalDateTime.now().plusHours(hoursBeforeStart)
                .isBefore(getStartDateTime());
    }

    public void markBooked() {
        this.status = SlotStatus.BOOKED;
    }

    public void markFree() {
        this.status = SlotStatus.FREE;
        this.blockReason = null;
    }

    public void block(String reason) {
        this.status = SlotStatus.BLOCKED;
        this.blockReason = reason;
    }

    public void markManual() {
        this.status = SlotStatus.MANUAL_BOOKING;
        this.manual = true;
    }
}