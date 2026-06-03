package org.example.groombook.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.time.Duration;


@Entity
@Table(
        name = "template_days",
        uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "day_of_week"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ScheduleTemplate template;

    /** День недели: 1=Пн, 2=Вт, 3=Ср, 4=Чт, 5=Пт, 6=Сб, 7=Вс */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "is_working", nullable = false)
    @Builder.Default
    private boolean working = true;

    /** null если выходной день */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** null если выходной день */
    @Column(name = "end_time")
    private LocalTime endTime;

    // ── Доменная логика ────────────────────────────────────────────────────────

    /** Количество слотов в рабочем дне с учётом длительности из шаблона */
    public int countSlots(int slotDurationHours) {
        if (!working || startTime == null || endTime == null) return 0;
        int totalMinutes = (int) Duration.between(startTime, endTime).toMinutes();
        return totalMinutes / (slotDurationHours * 60);
    }
}
