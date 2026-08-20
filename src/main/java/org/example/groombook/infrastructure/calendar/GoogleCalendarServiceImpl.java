package org.example.groombook.infrastructure.calendar;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.model.Booking;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.service.GoogleCalendarService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCalendarServiceImpl implements GoogleCalendarService {

    private final Calendar calendarClient;

    /** ID календаря мастера — задаётся в application.yml.
     *  Обычно это email мастера или "primary" для основного календаря. */
    @Value("${grooming.google.calendar-id:primary}")
    private String calendarId;

    /** Временная зона мастера */
    @Value("${grooming.timezone:Europe/Moscow}")
    private String timezone;

    @Override
    public String createEvent(Booking booking) {
        Event event = buildEvent(booking);
        try {
            Event created = calendarClient.events()
                    .insert(calendarId, event)
                    .execute();
            log.info("Создано событие Calendar id={} для брони #{}",
                    created.getId(), booking.getId());
            return created.getId();
        } catch (IOException e) {
            log.error("Ошибка создания события в Google Calendar для брони #{}: {}", 
                    booking.getId(), e.getMessage(), e);
            throw new RuntimeException(
                    "Ошибка создания события в Google Calendar: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteEvent(String eventId) {
        try {
            calendarClient.events().delete(calendarId, eventId).execute();
            log.info("Удалено событие Calendar id={}", eventId);
        } catch (IOException e) {
            // Если событие уже удалено — не бросаем исключение (идемпотентность)
            log.warn("Не удалось удалить событие Calendar id={}: {}", eventId, e.getMessage());
        }
    }

    @Override
    public void updateEvent(String eventId, Booking booking) {
        Event event = buildEvent(booking);
        try {
            calendarClient.events().update(calendarId, eventId, event).execute();
            log.info("Обновлено событие Calendar id={} для брони #{}",
                    eventId, booking.getId());
        } catch (IOException e) {
            log.error("Ошибка обновления события в Google Calendar id={} для брони #{}: {}", 
                    eventId, booking.getId(), e.getMessage(), e);
            throw new RuntimeException(
                    "Ошибка обновления события в Google Calendar: " + e.getMessage(), e);
        }
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /**
     * Строим Event из данных брони.
     *
     * Формат в календаре мастера:
     * Название:  "🐾 Рекс (Иван Петров)"  или  "🐾 Рекс (Иван Петров) [договорная]"
     * Описание:  телефон + комментарий клиента
     */
    private Event buildEvent(Booking booking) {
        ZoneId zoneId = ZoneId.of(timezone);

        LocalDateTime start = LocalDateTime.of(
                booking.getSlot().getDate(),
                booking.getSlot().getStartTime());
        LocalDateTime end = LocalDateTime.of(
                booking.getSlot().getDate(),
                booking.getSlot().getEndTime());

        ZonedDateTime zonedStart = start.atZone(zoneId);
        ZonedDateTime zonedEnd   = end.atZone(zoneId);

        boolean isManual = booking.getBookingType() == BookingType.MANUAL;

        String title = String.format("🐾 %s (%s)%s",
                booking.getPet().getName(),
                booking.getClient().getName(),
                isManual ? " [договорная]" : "");

        String description = buildDescription(booking);

        return new Event()
                .setSummary(title)
                .setDescription(description)
                .setStart(new EventDateTime()
                        .setDateTime(new DateTime(zonedStart.toInstant().toEpochMilli()))
                        .setTimeZone(timezone))
                .setEnd(new EventDateTime()
                        .setDateTime(new DateTime(zonedEnd.toInstant().toEpochMilli()))
                        .setTimeZone(timezone));
    }

    private String buildDescription(Booking booking) {
        StringBuilder sb = new StringBuilder();
        sb.append("📞 ").append(booking.getClient().getPhone()).append("\n");

        if (booking.getClientComment() != null && !booking.getClientComment().isBlank()) {
            sb.append("💬 ").append(booking.getClientComment()).append("\n");
        }

        if (booking.getPet().getDifficulty() != null) {
            switch (booking.getPet().getDifficulty()) {
                case HARD    -> sb.append("⚠️ Сложный питомец\n");
                case REFUSED -> sb.append("🚫 REFUSED — не должно попасть сюда\n");
                default      -> {}
            }
        }

        if (booking.getPet().getDifficultyNote() != null) {
            sb.append("📝 ").append(booking.getPet().getDifficultyNote());
        }

        return sb.toString();
    }
}
