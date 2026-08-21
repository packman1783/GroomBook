package org.example.groombook.service;

import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.repository.BookingRepository;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.DayOverrideRepository;
import org.example.groombook.repository.PetRepository;
import org.example.groombook.repository.ScheduleTemplateRepository;
import org.example.groombook.repository.TimeSlotRepository;
import org.example.groombook.service.dto.MonthlyReport;
import org.example.groombook.service.dto.WeeklyReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private TimeSlotRepository timeSlotRepository;
    @Mock
    private ScheduleTemplateRepository templateRepository;
    @Mock
    private DayOverrideRepository overrideRepository;

    @InjectMocks
    private StatisticsService statisticsService;

    @Test
    void getMonthlyReport_shouldCalculateCorrectly() {
        YearMonth month = YearMonth.of(2023, 10);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        Client client1 = Client.builder().id(1L).build();
        Client client2 = Client.builder().id(2L).build();

        TimeSlot slot1 = TimeSlot.builder()
                .date(from)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 0))
                .build();
        
        Booking b1 = Booking.builder()
                .client(client1)
                .slot(slot1)
                .status(BookingStatus.COMPLETED)
                .bookingType(BookingType.STANDARD)
                .build();

        when(bookingRepository.findActiveInDateRange(from, to)).thenReturn(List.of(b1));
        when(timeSlotRepository.findFreeSlotsBetween(from, to)).thenReturn(Collections.emptyList());
        when(bookingRepository.countUniqueClientsInPeriod(from, to)).thenReturn(1L);
        // client1 has no other bookings before this month in this mock setup
        when(bookingRepository.findAllByClientId(1L)).thenReturn(List.of(b1));

        MonthlyReport report = statisticsService.getMonthlyReport(month);

        assertNotNull(report);
        assertEquals(month, report.getMonth());
        assertEquals(1, report.getCompleted());
        assertEquals(1, report.getTotalUniqueClients());
        assertEquals(1, report.getNewClients());
        assertEquals(0, report.getReturningClients());
        assertEquals(2, report.getWorkingHours()); // 1 slot * 2 hours
    }

    @Test
    void getWeeklyReport_shouldCalculateCorrectly() {
        LocalDate from = LocalDate.of(2023, 10, 2); // Monday
        LocalDate to = from.plusDays(6);

        when(templateRepository.findActive()).thenReturn(java.util.Optional.empty());
        when(bookingRepository.findActiveInDateRange(from, to)).thenReturn(Collections.emptyList());
        when(timeSlotRepository.findByDateBetween(from, to)).thenReturn(Collections.emptyList());
        when(overrideRepository.findByDateBetweenOrderByDateAsc(from, to)).thenReturn(Collections.emptyList());

        WeeklyReport report = statisticsService.getWeeklyReport(from, to);

        assertNotNull(report);
        assertEquals(from, report.getStartDate());
        assertEquals(to, report.getEndDate());
        assertEquals("Нет активного шаблона", report.getTemplateName());
    }
}
