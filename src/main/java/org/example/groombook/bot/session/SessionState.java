package org.example.groombook.bot.session;

/**
 * Состояние диалога с пользователем.
 * Telegram не имеет встроенных форм для ввода текста —
 * многошаговые сценарии (регистрация, бронирование, причина блокировки)
 * реализуются через ожидание следующего текстового сообщения в нужном состоянии.
 */
public enum SessionState {

    /**
     * Нет активного диалога — обычные команды обрабатываются как обычно
     */
    NONE,

    // ── Регистрация клиента ───────────────────────────────────────────────────
    AWAITING_NAME,
    AWAITING_PHONE,

    // ── Добавление питомца ────────────────────────────────────────────────────
    AWAITING_PET_NAME,

    // ── Бронирование ──────────────────────────────────────────────────────────
    AWAITING_BOOKING_COMMENT,

    // ── Действия мастера, требующие текстового ввода ──────────────────────────
    AWAITING_REJECT_REASON,
    AWAITING_CANCEL_REASON,
    AWAITING_MASTER_NOTE,
    AWAITING_BLOCK_DATE,
    AWAITING_BLOCK_REASON,
    AWAITING_VACATION_RANGE,
    AWAITING_TEMPLATE_ACTIVATE_DATE,
    AWAITING_MANUAL_BOOKING_DATE,
    AWAITING_MANUAL_BOOKING_TIME,
    AWAITING_MANUAL_CLIENT_PHONE,
    AWAITING_MANUAL_COMMENT
}
