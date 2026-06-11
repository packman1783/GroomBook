package org.example.groombook.service.dto;

import lombok.Builder;
import lombok.Getter;

import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;

import java.time.LocalDate;
import java.util.List;

/**
 * Статистика по конкретному клиенту.
 * Используется в команде мастера при просмотре профиля клиента.
 */
@Getter
@Builder
public class ClientStats {

    private final Client     client;
    private final List<Pet>  pets;

    private final long      totalVisits;       // всего завершённых визитов
    private final long      cancelledByClient; // отменено клиентом
    private final int       noShowCount;       // не пришёл без предупреждения
    private final LocalDate firstVisit;        // дата первого визита
    private final LocalDate lastVisit;         // дата последнего визита
    private final long      daysSinceLastVisit;

    public boolean isInactive(int thresholdDays) {
        return daysSinceLastVisit >= thresholdDays;
    }
}
