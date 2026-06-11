package org.example.groombook.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.YearMonth;

/**
 * Месячный отчёт для мастера.
 * Формируется в StatisticsService и отправляется в Telegram.
 */
@Getter
@Builder
public class MonthlyReport {

    private final YearMonth month;

    // Рабочие дни и часы
    private final int workingDays;
    private final int totalSlots;         // всего слотов было доступно
    private final int workingHours;       // totalSlots * длительность слота
    private final int manualBookingHours; // часы из договорных записей

    // Брони
    private final long completed;         // завершённых визитов
    private final long cancelled;         // отменено (клиентом + мастером)
    private final long noShow;            // не пришли
    private final long pending;           // ожидают подтверждения на конец месяца

    // Клиенты
    private final long totalUniqueClients;  // уникальных клиентов за месяц
    private final long newClients;          // впервые пришли в этом месяце
    private final long returningClients;    // повторные клиенты

    // Вычисляемые показатели
    public double getLoadPercent() {
        if (totalSlots == 0) return 0.0;
        return (double) completed / totalSlots * 100;
    }

    public double getCancellationRate() {
        long total = completed + cancelled + noShow;
        if (total == 0) return 0.0;
        return (double) (cancelled + noShow) / total * 100;
    }
}
