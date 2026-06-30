package org.example.groombook.service;


import org.example.groombook.model.Booking;

/**
 * Контракт отправки уведомлений.
 * Сервисы BookingService и другие зависят от этого интерфейса —
 * они не знают что внутри Telegram. Завтра можно добавить SMS или email,
 * не меняя ни строчки в BookingService.
 */
public interface NotificationService {

    // ── Уведомления мастеру ───────────────────────────────────────────────────

    /**
     * Новая заявка от клиента.
     * Мастер видит: имя, телефон, питомец, время, комментарий.
     * Кнопки: [Подтвердить] [Отклонить]
     */
    void notifyMasterNewBooking(Booking booking);

    /**
     * Клиент отменил свою бронь.
     * Мастер видит: кто отменил, какой слот освободился.
     */
    void notifyMasterClientCancelled(Booking booking);

    // ── Уведомления клиенту ───────────────────────────────────────────────────

    /**
     * Мастер подтвердил бронь.
     * Клиент видит: дата, время, адрес мастера.
     */
    void notifyClientConfirmed(Booking booking);

    /**
     * Мастер отменил или отклонил бронь.
     * Клиент видит нейтральное сообщение без раскрытия причины.
     */
    void notifyClientCancelled(Booking booking, String reason);

    /**
     * Напоминание о предстоящем визите.
     * Отправляется клиенту за 24 часа до начала слота.
     */
    void sendReminderToClient(Booking booking);

    /**
     * Прямое сообщение клиенту по telegramId.
     * Используется для рассылки (например, "давно не заходили").
     */
    void sendMessageToClient(Long telegramId, String message);
}
