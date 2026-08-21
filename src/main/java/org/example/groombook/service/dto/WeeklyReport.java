package org.example.groombook.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.Map;

@Getter
@Builder
public class WeeklyReport {
    private final LocalDate startDate;
    private final LocalDate endDate;

    // Информация о шаблоне
    private final String templateName;
    private final int slotDurationHours;
    private final int workingDaysCount;
    private final int holidayDaysCount;

    // Статистика по слотам и записям
    private final long totalSlots;
    private final long completedBookings;
    private final long cancelledBookings;
    private final long noShowBookings;
    private final long manualBookings;
    private final long blockedSlots;

    // Дополнительно
    private final Map<LocalDate, String> overrides; // Дата -> Причина переопределения (выходной/блока)

    public double getLoadPercent() {
        if (totalSlots == 0) return 0.0;
        return (double) (completedBookings + manualBookings) / totalSlots * 100;
    }
}
