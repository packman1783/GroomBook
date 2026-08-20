package org.example.groombook.infrastructure.calendar;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;

import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.model.enums.PetDifficulty;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceImplTest {

    @Mock
    private Calendar calendarClient;

    @InjectMocks
    private GoogleCalendarServiceImpl googleCalendarService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(googleCalendarService, "calendarId", "primary");
        ReflectionTestUtils.setField(googleCalendarService, "timezone", "Europe/Moscow");
    }

    @Test
    void buildEvent_ShouldCreateCorrectEvent() {
        LocalDate date = LocalDate.of(2026, 8, 25);
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(12, 0);
        
        TimeSlot slot = TimeSlot.builder()
                .date(date)
                .startTime(start)
                .endTime(end)
                .build();
        
        Client client = Client.builder()
                .name("Иван Петров")
                .phone("+79991234567")
                .build();
        
        Pet pet = Pet.builder()
                .name("Рекс")
                .difficulty(PetDifficulty.HARD)
                .difficultyNote("Кусается")
                .build();
        
        Booking booking = Booking.builder()
                .id(1L)
                .slot(slot)
                .client(client)
                .pet(pet)
                .bookingType(BookingType.STANDARD)
                .clientComment("Нужна стрижка когтей")
                .build();

        // Для простоты проверим через Reflection для этого теста
        Event event = ReflectionTestUtils.invokeMethod(googleCalendarService, "buildEvent", booking);

        assertThat(event.getSummary()).isEqualTo("🐾 Рекс (Иван Петров)");
        assertThat(event.getDescription())
                .contains("📞 +79991234567")
                .contains("💬 Нужна стрижка когтей")
                .contains("⚠️ Сложный питомец")
                .contains("📝 Кусается");

        ZoneId zoneId = ZoneId.of("Europe/Moscow");
        long expectedStart = ZonedDateTime.of(date, start, zoneId).toInstant().toEpochMilli();
        long expectedEnd = ZonedDateTime.of(date, end, zoneId).toInstant().toEpochMilli();

        assertThat(event.getStart().getDateTime().getValue()).isEqualTo(expectedStart);
        assertThat(event.getEnd().getDateTime().getValue()).isEqualTo(expectedEnd);
        assertThat(event.getStart().getTimeZone()).isEqualTo("Europe/Moscow");
    }
}
