package org.example.groombook.bot.session;

/**
 * Состояние многошагового диалога с пользователем.
 */
public enum SessionState {

    /**
     * Нет активного диалога
     */
    NONE,

    /**
     * Регистрация клиента
     */
    AWAITING_NAME,
    AWAITING_PHONE,

    /**
     * Добавление питомца
     */
    AWAITING_PET_NAME,

    /**
     * Бронирование
     */
    AWAITING_BOOKING_COMMENT,

    /**
     * Действия мастера
     */
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
    AWAITING_MANUAL_COMMENT,

    /**
     * Wizard создания шаблона
     */
    AWAITING_TEMPLATE_NAME,
    AWAITING_TEMPLATE_DAYS,
    AWAITING_TEMPLATE_START_TIME,
    AWAITING_TEMPLATE_END_TIME,
    AWAITING_TEMPLATE_SLOT_DURATION,
    AWAITING_TEMPLATE_CONFIRM
}
