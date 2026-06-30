package org.example.groombook.service;

import org.example.groombook.model.Booking;

/**
 * Контракт синхронизации с Google Calendar.
 * Calendar — зеркало БД, не источник правды.
 * Все методы могут бросить исключение при проблемах с API —
 * вызывающий код обязан обработать это gracefully (не откатывать бронь).
 */
public interface GoogleCalendarService {

    /**
     * Создать событие в календаре мастера при подтверждении брони.
     * Возвращает gcal_event_id для сохранения в booking.
     * <p>
     * Формат события:
     * Название: "Рекс (Иван Петров)"
     * Время:    слот из booking
     * Описание: телефон клиента + комментарий
     */
    String createEvent(Booking booking);

    /**
     * Удалить событие из календаря при отмене брони или no-show.
     * Если eventId не найден — молча игнорировать (идемпотентность).
     */
    void deleteEvent(String eventId);

    /**
     * Обновить событие — например, если мастер изменил время договорной записи.
     */
    void updateEvent(String eventId, Booking booking);
}
