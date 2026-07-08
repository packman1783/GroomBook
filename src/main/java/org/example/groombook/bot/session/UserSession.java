package org.example.groombook.bot.session;

import lombok.Data;

/**
 * Состояние диалога с конкретным пользователем + временные данные
 * накопленные в процессе многошагового сценария.
 * <p>
 * Пример сценария бронирования:
 * 1. Пользователь выбрал дату    → pendingDate = ...
 * 2. Пользователь выбрал слот    → pendingSlotId = ...
 * 3. Пользователь выбрал питомца → pendingPetId = ...
 * 4. Состояние → AWAITING_BOOKING_COMMENT
 * 5. Пользователь написал комментарий → создаём бронь, сбрасываем сессию
 */
@Data
public class UserSession {

    private SessionState state = SessionState.NONE;

    // Регистрация
    private String pendingName;
    private String pendingPhone;

    // Добавление питомца
    private String pendingPetName;

    // Бронирование
    private Long pendingSlotId;
    private Long pendingPetId;

    // Действия требующие ID существующей сущности + последующий текстовый ввод
    private Long pendingBookingId;
    private Long pendingClientId;

    // Блокировка слота
    private java.time.LocalDate pendingBlockDate;
    private Long pendingBlockSlotId;

    // Договорная запись
    private Long pendingManualClientId;
    private Long pendingManualPetId;
    private java.time.LocalDate pendingManualDate;

    /**
     * Сброс состояния после завершения или отмены сценария
     */
    public void reset() {
        this.state = SessionState.NONE;
        this.pendingName = null;
        this.pendingPhone = null;
        this.pendingPetName = null;
        this.pendingSlotId = null;
        this.pendingPetId = null;
        this.pendingBookingId = null;
        this.pendingClientId = null;
        this.pendingBlockDate = null;
        this.pendingBlockSlotId = null;
        this.pendingManualClientId = null;
        this.pendingManualPetId = null;
        this.pendingManualDate = null;
    }
}
