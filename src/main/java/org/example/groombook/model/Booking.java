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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;

import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Один слот — одна активная бронь. Контроль через partial unique index в БД */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private TimeSlot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", nullable = false, length = 20)
    @Builder.Default
    private BookingType bookingType = BookingType.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    /** Комментарий клиента при бронировании */
    @Column(name = "client_comment", columnDefinition = "TEXT")
    private String clientComment;

    /** Заметка мастера после визита — видит только мастер */
    @Column(name = "master_note", columnDefinition = "TEXT")
    private String masterNote;

    /** Клиент не пришёл без предупреждения */
    @Column(name = "no_show", nullable = false)
    @Builder.Default
    private boolean noShow = false;

    /** ID события в Google Calendar — для синхронизации при изменениях */
    @Column(name = "gcal_event_id", length = 200)
    private String gcalEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Доменная логика ────────────────────────────────────────────────────────

    public boolean isPending() {
        return status == BookingStatus.PENDING;
    }

    public boolean isConfirmed() {
        return status == BookingStatus.CONFIRMED;
    }

    public boolean isActive() {
        return status == BookingStatus.PENDING || status == BookingStatus.CONFIRMED;
    }

    /** Клиент может отменить только если до начала слота > 24 часов */
    public boolean isCancellableByClient() {
        return isActive() && slot.isCancellableByClient(24);
    }

    public void confirm(String gcalEventId) {
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.gcalEventId = gcalEventId;
    }

    public void complete(String masterNote) {
        this.status = BookingStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.masterNote = masterNote;
    }

    public void cancelByClient() {
        this.status = BookingStatus.CANCELLED_BY_CLIENT;
    }

    public void cancelByMaster() {
        this.status = BookingStatus.CANCELLED_BY_MASTER;
    }

    public void markNoShow() {
        this.status = BookingStatus.NO_SHOW;
        this.noShow = true;
    }
}
