package org.example.groombook.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "schedule_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Человекочитаемое название: "Стандартный", "Высокий сезон", "Зима" */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Только один шаблон может быть активным одновременно.
     *  Контроль через unique partial index в БД + проверка в ScheduleService */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    /** С какой даты применяется шаблон */
    @Column(name = "active_from")
    private LocalDate activeFrom;

    /** До какой даты применяется. null = бессрочно */
    @Column(name = "active_until")
    private LocalDate activeUntil;

    /** Длительность одного слота в часах: 1, 2 (по умолчанию) или 3 */
    @Column(name = "slot_duration_hours", nullable = false)
    @Builder.Default
    private int slotDurationHours = 2;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Конфигурация рабочих дней недели */
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TemplateDay> days = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Доменная логика ────────────────────────────────────────────────────────

    /** Возвращает конфигурацию дня недели (1=Пн … 7=Вс) */
    public TemplateDay getDayConfig(int dayOfWeek) {
        return days.stream()
                .filter(d -> d.getDayOfWeek() == dayOfWeek)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Не найдена конфигурация для дня недели: " + dayOfWeek));
    }

    /** Применяется ли шаблон на указанную дату */
    public boolean isApplicableTo(LocalDate date) {
        if (activeFrom != null && date.isBefore(activeFrom)) return false;
        if (activeUntil != null && date.isAfter(activeUntil)) return false;
        return true;
    }

    public void activate(LocalDate from, LocalDate until) {
        this.active = true;
        this.activeFrom = from;
        this.activeUntil = until;
    }

    public void deactivate() {
        this.active = false;
    }
}
