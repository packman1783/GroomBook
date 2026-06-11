package org.example.groombook.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.exception.ClientNotFoundException;
import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.repository.BookingRepository;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.PetRepository;
import org.example.groombook.repository.TimeSlotRepository;
import org.example.groombook.service.dto.ClientStats;
import org.example.groombook.service.dto.MonthlyReport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    // Порог для пометки клиента как "давно не приходил"
    private static final int INACTIVE_THRESHOLD_DAYS = 60;

    private final BookingRepository bookingRepository;
    private final ClientRepository clientRepository;
    private final PetRepository petRepository;
    private final TimeSlotRepository timeSlotRepository;

    // Месячный отчёт

    /**
     * Сформировать месячный отчёт для мастера.
     * <p>
     * Пример вывода в боте:
     * ─────────────────────────
     * Апрель 2025
     * Рабочих слотов: 84
     * Рабочих часов:  168 (из них договорных: 8)
     * Завершено:      63
     * Отменено:       4  (6.3%)
     * No-show:        1
     * Загрузка:       75%
     * ─────────────────────────
     * Клиентов всего:  22
     * Новых:           8
     * Повторных:       14
     * ─────────────────────────
     */
    @Transactional(readOnly = true)
    public MonthlyReport getMonthlyReport(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        // Все брони за месяц
        List<Booking> allBookings = bookingRepository.findActiveInDateRange(from, to);

        // Считаем брони по статусам
        Map<BookingStatus, Long> byStatus = allBookings.stream()
                .collect(Collectors.groupingBy(Booking::getStatus, Collectors.counting()));

        long completed = byStatus.getOrDefault(BookingStatus.COMPLETED, 0L);
        long cancelled = byStatus.getOrDefault(BookingStatus.CANCELLED_BY_CLIENT, 0L)
                + byStatus.getOrDefault(BookingStatus.CANCELLED_BY_MASTER, 0L);
        long noShow = byStatus.getOrDefault(BookingStatus.NO_SHOW, 0L);
        long pending = byStatus.getOrDefault(BookingStatus.PENDING, 0L)
                + byStatus.getOrDefault(BookingStatus.CONFIRMED, 0L);

        // Часы из договорных записей
        int manualHours = allBookings.stream()
                .filter(b -> b.getBookingType() == BookingType.MANUAL)
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .mapToInt(b -> (int) ChronoUnit.HOURS.between(
                        b.getSlot().getStartTime(), b.getSlot().getEndTime()))
                .sum();

        // Слоты за месяц — считаем по БД
        long totalSlots = timeSlotRepository
                .findFreeSlotsBetween(from, to).size() + completed + noShow;

        // Длительность слота из первой брони (или 2 часа по умолчанию)
        int slotHours = allBookings.stream()
                .findFirst()
                .map(b -> (int) ChronoUnit.HOURS.between(
                        b.getSlot().getStartTime(), b.getSlot().getEndTime()))
                .orElse(2);

        // Уникальные клиенты за месяц
        long totalUniqueClients = bookingRepository
                .countUniqueClientsInPeriod(from, to);

        // Новые клиенты — первый визит именно в этом месяце
        long newClients = allBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .map(Booking::getClient)
                .distinct()
                .filter(c -> isFirstVisitInPeriod(c.getId(), from, to))
                .count();

        long returningClients = Math.max(0, totalUniqueClients - newClients);

        // Рабочие дни — дни у которых были слоты
        long workingDays = allBookings.stream()
                .map(b -> b.getSlot().getDate())
                .distinct()
                .count();

        return MonthlyReport.builder()
                .month(month)
                .workingDays((int) workingDays)
                .totalSlots((int) totalSlots)
                .workingHours((int) (totalSlots * slotHours))
                .manualBookingHours(manualHours)
                .completed(completed)
                .cancelled(cancelled)
                .noShow(noShow)
                .pending(pending)
                .totalUniqueClients(totalUniqueClients)
                .newClients(newClients)
                .returningClients(returningClients)
                .build();
    }

    // Статистика по клиенту

    /**
     * Полная статистика по клиенту — для просмотра мастером.
     * <p>
     * Пример вывода в боте:
     * ─────────────────────────
     * Иван Петров  +7-999-...
     * Питомцы: Рекс , Пушок
     * ─────────────────────────
     * Всего визитов:    12
     * Отменил сам:       1
     * No-show:           0
     * Первый визит: 10 января 2024
     * Последний:    5 апреля 2025
     * ─────────────────────────
     */
    @Transactional(readOnly = true)
    public ClientStats getClientStats(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        List<Booking> allBookings = bookingRepository.findAllByClientId(clientId);

        long totalVisits = allBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .count();

        long cancelledByClient = allBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CANCELLED_BY_CLIENT)
                .count();

        // Даты завершённых визитов для first/last
        List<LocalDate> visitDates = allBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .map(b -> b.getSlot().getDate())
                .sorted()
                .toList();

        LocalDate firstVisit = visitDates.isEmpty() ? null : visitDates.get(0);
        LocalDate lastVisit = visitDates.isEmpty() ? null
                : visitDates.get(visitDates.size() - 1);

        long daysSinceLastVisit = lastVisit == null ? Long.MAX_VALUE
                : ChronoUnit.DAYS.between(lastVisit, LocalDate.now());

        return ClientStats.builder()
                .client(client)
                .pets(petRepository.findByClientId(clientId))
                .totalVisits(totalVisits)
                .cancelledByClient(cancelledByClient)
                .noShowCount(client.getNoShowCount())
                .firstVisit(firstVisit)
                .lastVisit(lastVisit)
                .daysSinceLastVisit(daysSinceLastVisit)
                .build();
    }

    // Списки клиентов

    /**
     * Клиенты не приходившие более 60 дней.
     * Мастер может написать им напоминание.
     */
    @Transactional(readOnly = true)
    public List<Client> getInactiveClients() {
        return clientRepository.findInactiveClients(
                java.time.LocalDateTime.now().minusDays(INACTIVE_THRESHOLD_DAYS));
    }

    /**
     * Топ клиентов по количеству визитов за всё время.
     * Для просмотра самых постоянных клиентов.
     */
    @Transactional(readOnly = true)
    public List<ClientStats> getTopClients(int limit) {
        return clientRepository.findAll().stream()
                .map(c -> getClientStats(c.getId()))
                .filter(s -> s.getTotalVisits() > 0)
                .sorted((a, b) -> Long.compare(b.getTotalVisits(), a.getTotalVisits()))
                .limit(limit)
                .toList();
    }

    // Приватные методы

    /**
     * Проверяет что первый визит клиента попадает в указанный период.
     * Нужно для разделения "новый клиент" / "повторный клиент" в отчёте.
     */
    private boolean isFirstVisitInPeriod(Long clientId,
                                         LocalDate from, LocalDate to) {
        List<Booking> allCompleted = bookingRepository.findAllByClientId(clientId)
                .stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .toList();

        if (allCompleted.isEmpty()) return false;

        LocalDate firstVisit = allCompleted.stream()
                .map(b -> b.getSlot().getDate())
                .min(LocalDate::compareTo)
                .orElse(null);

        return firstVisit != null
                && !firstVisit.isBefore(from)
                && !firstVisit.isAfter(to);
    }
}
