package org.example.groombook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.exception.ClientNotFoundException;
import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.DayOverride;
import org.example.groombook.model.ScheduleTemplate;
import org.example.groombook.model.TemplateDay;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.model.enums.SlotStatus;
import org.example.groombook.repository.BookingRepository;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.DayOverrideRepository;
import org.example.groombook.repository.PetRepository;
import org.example.groombook.repository.ScheduleTemplateRepository;
import org.example.groombook.repository.TimeSlotRepository;
import org.example.groombook.service.dto.ClientStats;
import org.example.groombook.service.dto.MonthlyReport;
import org.example.groombook.service.dto.WeeklyReport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис расчета аналитической и операционной статистики для мастера.
 * <p>
 * Формирует сводные данные для ежемесячных отчетов, собирает индивидуальную историю визитов
 * клиентов и рассчитывает показатели удержания/неактивности аудитории.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    /**
     * Порог (в днях) для определения клиента как "неактивного" / "давно не приходил".
     */
    private static final int INACTIVE_THRESHOLD_DAYS = 60;

    private final BookingRepository bookingRepository;
    private final ClientRepository clientRepository;
    private final PetRepository petRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final ScheduleTemplateRepository templateRepository;
    private final DayOverrideRepository overrideRepository;

    // Месячный отчёт

    /**
     * Агрегирует и высчитывает ключевые показатели работы мастера за указанный месяц.
     * <p>
     * Считает общее время работы, количество выполненных/отмененных визитов, отработано часов по договорным записям,
     * а также производит разделение аудитории на "новых" и "повторных" клиентов за период.
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

    @Transactional(readOnly = true)
    public WeeklyReport getWeeklyReport(LocalDate from, LocalDate to) {
        // 1. Информация о шаблоне
        ScheduleTemplate template = templateRepository.findActive().orElse(null);
        String templateName = template != null ? template.getName() : "Нет активного шаблона";
        int slotDuration = template != null ? template.getSlotDurationHours() : 0;

        long workingDaysCount = 0;
        long holidayDaysCount = 0;

        if (template != null) {
            workingDaysCount = template.getDays().stream().filter(TemplateDay::isWorking).count();
            holidayDaysCount = 7 - workingDaysCount;
        }

        // 2. Статистика по записям и слотам
        List<Booking> bookings = bookingRepository.findActiveInDateRange(from, to);
        List<TimeSlot> slots = timeSlotRepository.findByDateBetween(from, to);

        long completed = bookings.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        long cancelled = bookings.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED_BY_CLIENT
                || b.getStatus() == BookingStatus.CANCELLED_BY_MASTER).count();
        long noShow = bookings.stream().filter(b -> b.getStatus() == BookingStatus.NO_SHOW).count();

        long manual = slots.stream().filter(s -> s.getStatus() == SlotStatus.MANUAL_BOOKING).count();
        long blocked = slots.stream().filter(s -> s.getStatus() == SlotStatus.BLOCKED).count();
        long totalSlots = slots.size();

        // 3. Переопределения
        List<DayOverride> overrides = overrideRepository.findByDateBetweenOrderByDateAsc(from, to);
        Map<LocalDate, String> overridesMap = overrides.stream()
                .collect(Collectors.toMap(
                        DayOverride::getDate,
                        o -> o.getReason() != null ? o.getReason() : o.getOverrideType().toString()
                ));

        return WeeklyReport.builder()
                .startDate(from)
                .endDate(to)
                .templateName(templateName)
                .slotDurationHours(slotDuration)
                .workingDaysCount((int) workingDaysCount)
                .holidayDaysCount((int) holidayDaysCount)
                .totalSlots(totalSlots)
                .completedBookings(completed)
                .cancelledBookings(cancelled)
                .noShowBookings(noShow)
                .manualBookings(manual)
                .blockedSlots(blocked)
                .overrides(overridesMap)
                .build();
    }

    // Статистика по клиенту

    /**
     * Формирует подробную индивидуальную статистику по заданному клиенту.
     * Подсчитывает общее число успешных посещений, количество отмен, неявок, а также даты первого и последнего визитов.
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
     * Возвращает список клиентов, не посещавших салон более {@value #INACTIVE_THRESHOLD_DAYS} дней.
     */
    @Transactional(readOnly = true)
    public List<Client> getInactiveClients() {
        return clientRepository.findInactiveClients(
                java.time.LocalDateTime.now().minusDays(INACTIVE_THRESHOLD_DAYS));
    }

    /**
     * Формирует топ постоянных клиентов по количеству завершенных посещений за всё время.
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
     * Проверяет, приходится ли самый первый успешный визит клиента именно на выбранный календарный период.
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
