package org.example.groombook.bot.session;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Состояние диалога с конкретным пользователем и временные данные
 * многошаговых сценариев.
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

    // Действия мастера
    private Long pendingBookingId;
    private Long pendingClientId;
    private Long pendingTemplateId;

    // Блокировка слота
    private LocalDate pendingBlockDate;
    private Long pendingBlockSlotId;

    // Договорная запись
    private Long pendingManualClientId;
    private Long pendingManualPetId;
    private LocalDate pendingManualDate;
    private LocalTime pendingManualTime;

    // Wizard создания шаблона
    private String pendingTemplateName;
    private Set<Integer> pendingTemplateWorkingDays = new HashSet<>();
    private LocalTime pendingTemplateStartTime;
    private LocalTime pendingTemplateEndTime;
    private Integer pendingTemplateSlotDuration;
    private Integer pendingTemplateMessageId;

    /**
     * Сброс состояния после завершения или отмены сценария.
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
        this.pendingTemplateId = null;
        this.pendingBlockDate = null;
        this.pendingBlockSlotId = null;
        this.pendingManualClientId = null;
        this.pendingManualPetId = null;
        this.pendingManualDate = null;
        this.pendingManualTime = null;
        this.pendingTemplateName = null;
        this.pendingTemplateWorkingDays = new HashSet<>();
        this.pendingTemplateStartTime = null;
        this.pendingTemplateEndTime = null;
        this.pendingTemplateSlotDuration = null;
        this.pendingTemplateMessageId = null;
    }
}
