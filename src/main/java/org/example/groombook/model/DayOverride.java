package org.example.groombook.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.example.groombook.enums.OverrideType;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "day_overrides")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DayOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Дата переопределения. Уникальна — один override на одну дату */
    @Column(name = "date", nullable = false, unique = true)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_type", nullable = false, length = 20)
    private OverrideType overrideType;

    /** false = нерабочий день (праздник, отпуск) */
    @Column(name = "is_working", nullable = false)
    private boolean working;

    /** null если нерабочий день */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** null если нерабочий день */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** Пояснение для мастера: "9 мая", "Отпуск", "Врач до обеда" */
    @Column(name = "reason", length = 200)
    private String reason;

    // ── Фабричные методы ───────────────────────────────────────────────────────

    public static DayOverride holiday(LocalDate date, String reason) {
        return DayOverride.builder()
                .date(date)
                .overrideType(OverrideType.HOLIDAY)
                .working(false)
                .reason(reason)
                .build();
    }

    public static DayOverride vacation(LocalDate date) {
        return DayOverride.builder()
                .date(date)
                .overrideType(OverrideType.VACATION)
                .working(false)
                .reason("Отпуск")
                .build();
    }

    public static DayOverride customHours(LocalDate date, LocalTime start,
                                          LocalTime end, String reason) {
        return DayOverride.builder()
                .date(date)
                .overrideType(OverrideType.CUSTOM_HOURS)
                .working(true)
                .startTime(start)
                .endTime(end)
                .reason(reason)
                .build();
    }

    public static DayOverride extraWorkingDay(LocalDate date, LocalTime start,
                                              LocalTime end) {
        return DayOverride.builder()
                .date(date)
                .overrideType(OverrideType.EXTRA_WORKING_DAY)
                .working(true)
                .startTime(start)
                .endTime(end)
                .reason("Рабочий день по договорённости")
                .build();
    }
}
