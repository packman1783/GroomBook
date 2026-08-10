package org.example.groombook.bot.handler;

import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.model.ScheduleTemplate;
import org.example.groombook.service.ScheduleService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateWizardHandler")
class TemplateWizardHandlerTest {

    @Mock
    ScheduleService scheduleService;
    @Mock
    SessionManager sessionManager;
    @Mock
    TelegramClient telegramClient;

    @InjectMocks
    TemplateWizardHandler wizardHandler;

    private UserSession session;

    private static final Long TELEGRAM_ID = 999L;
    private static final String CALLBACK_ID = "cb-test-123";

    @BeforeEach
    void setUp() throws TelegramApiException {
        session = new UserSession();
        lenient().when(sessionManager.get(TELEGRAM_ID)).thenReturn(session);

        Message mockMessage = mock(Message.class);

        lenient().when(mockMessage.getMessageId()).thenReturn(42);
        lenient().when(telegramClient.execute(any(SendMessage.class))).thenReturn(mockMessage);
        lenient().when(telegramClient.execute(any(AnswerCallbackQuery.class))).thenReturn(true);
        lenient().when(telegramClient.execute(any(EditMessageReplyMarkup.class))).thenReturn(mockMessage);
    }

    // startWizard

    @Nested
    @DisplayName("startWizard")
    class StartWizard {

        @Test
        @DisplayName("✅ сбрасывает сессию и переходит в AWAITING_TEMPLATE_NAME")
        void startWizard_resetsSessionAndSetsState() throws TelegramApiException {
            // Предустановим какое-то состояние — должно сброситься
            session.setState(SessionState.AWAITING_BOOKING_COMMENT);
            session.setPendingTemplateName("старое название");

            wizardHandler.startWizard(TELEGRAM_ID);

            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_NAME);
            assertThat(session.getPendingTemplateName()).isNull();
            assertThat(session.getPendingTemplateWorkingDays()).isEmpty();

            verify(telegramClient).execute(any(SendMessage.class));
        }
    }

    // handleTextInput — ввод названия (шаг 1)

    @Nested
    @DisplayName("handleTextInput — название")
    class HandleTextInputName {

        @BeforeEach
        void setNameState() {
            session.setState(SessionState.AWAITING_TEMPLATE_NAME);
            session.setPendingTemplateWorkingDays(new HashSet<>());
        }

        @Test
        @DisplayName("✅ корректное название → сохраняется, переход к выбору дней")
        void handleTextInput_validName_savesAndAdvances() throws TelegramApiException {
            wizardHandler.handleTextInput(TELEGRAM_ID, "Высокий сезон");

            assertThat(session.getPendingTemplateName()).isEqualTo("Высокий сезон");
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_DAYS);
            // Сообщение с клавиатурой дней отправлено и messageId сохранён
            assertThat(session.getPendingTemplateMessageId()).isEqualTo(42);

            verify(telegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("✅ название обрезается от пробелов по краям")
        void handleTextInput_trimmedName() {
            wizardHandler.handleTextInput(TELEGRAM_ID, "  Стандартный  ");

            assertThat(session.getPendingTemplateName()).isEqualTo("Стандартный");
        }

        @Test
        @DisplayName("❌ пустое название → ошибка, состояние не меняется")
        void handleTextInput_blankName_showsError() throws TelegramApiException {
            wizardHandler.handleTextInput(TELEGRAM_ID, "   ");

            // Состояние не изменилось — ждём повторного ввода
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_NAME);
            assertThat(session.getPendingTemplateName()).isNull();

            verify(telegramClient).execute(any(SendMessage.class)); // сообщение об ошибке
        }

        @Test
        @DisplayName("✅ состояние не AWAITING_TEMPLATE_NAME — ввод игнорируется")
        void handleTextInput_wrongState_ignored() throws TelegramApiException {
            session.setState(SessionState.AWAITING_BOOKING_COMMENT); // чужое состояние

            wizardHandler.handleTextInput(TELEGRAM_ID, "Название");

            assertThat(session.getPendingTemplateName()).isNull(); // не изменилось
            verify(telegramClient, never()).execute(any(SendMessage.class));
        }
    }

    // handleCallback — тогл дней (шаг 2)

    @Nested
    @DisplayName("handleCallback — выбор дней")
    class HandleCallbackDays {

        @BeforeEach
        void setDaysState() {
            session.setState(SessionState.AWAITING_TEMPLATE_DAYS);
            session.setPendingTemplateWorkingDays(new HashSet<>());
            session.setPendingTemplateMessageId(42);
        }

        @Test
        @DisplayName("✅ клик на день добавляет его в выбранные")
        void toggleDay_addsDay() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_TOGGLE_DAY, 1), CALLBACK_ID);

            assertThat(session.getPendingTemplateWorkingDays()).contains(1);
            // Клавиатура обновлена в том же сообщении
            verify(telegramClient).execute(any(EditMessageReplyMarkup.class));
        }

        @Test
        @DisplayName("✅ повторный клик на день убирает его из выбранных (тогл)")
        void toggleDay_removesAlreadySelectedDay() throws TelegramApiException {
            session.getPendingTemplateWorkingDays().add(1); // день уже выбран

            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_TOGGLE_DAY, 1), CALLBACK_ID);

            assertThat(session.getPendingTemplateWorkingDays()).doesNotContain(1);
        }

        @Test
        @DisplayName("✅ можно выбрать несколько дней")
        void toggleDay_multipledays() throws TelegramApiException {
            for (int day : new int[]{1, 2, 3, 4, 5}) {
                wizardHandler.handleCallback(TELEGRAM_ID,
                        CallbackData.build(CallbackData.TEMPLATE_TOGGLE_DAY, day), CALLBACK_ID);
            }

            assertThat(session.getPendingTemplateWorkingDays())
                    .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("❌ Готово без выбранных дней → ошибка, состояние не меняется")
        void daysDone_noDaysSelected_showsError() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_DAYS_DONE, CALLBACK_ID);

            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_DAYS);
            // Ответ на callback с сообщением об ошибке
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
            // Новое сообщение НЕ отправляется
            verify(telegramClient, never()).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("✅ Готово с выбранными днями → переход к выбору начала дня")
        void daysDone_withDays_advancesToStartTime() throws TelegramApiException {
            session.getPendingTemplateWorkingDays().addAll(Set.of(1, 2, 3, 4, 5));

            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_DAYS_DONE, CALLBACK_ID);

            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_START_TIME);
            verify(telegramClient).execute(any(SendMessage.class));
        }
    }

    // handleCallback — выбор времени начала (шаг 3)

    @Nested
    @DisplayName("handleCallback — время начала")
    class HandleCallbackStartTime {

        @BeforeEach
        void setStartTimeState() {
            session.setState(SessionState.AWAITING_TEMPLATE_START_TIME);
            session.setPendingTemplateWorkingDays(Set.of(1, 2, 3, 4, 5));
        }

        @Test
        @DisplayName("✅ выбор времени начала → сохраняется, переход к выбору конца")
        void startTimeSelected_savesAndAdvances() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_START_TIME, "09:00"), CALLBACK_ID);

            assertThat(session.getPendingTemplateStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_END_TIME);
            verify(telegramClient).execute(any(SendMessage.class));
        }
    }

    // handleCallback — выбор времени конца (шаг 4)

    @Nested
    @DisplayName("handleCallback — время окончания")
    class HandleCallbackEndTime {

        @BeforeEach
        void setEndTimeState() {
            session.setState(SessionState.AWAITING_TEMPLATE_END_TIME);
            session.setPendingTemplateStartTime(LocalTime.of(9, 0));
            session.setPendingTemplateWorkingDays(Set.of(1, 2, 3, 4, 5));
        }

        @Test
        @DisplayName("✅ время конца позже начала → сохраняется, переход к длительности")
        void endTimeSelected_validTime_savesAndAdvances() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_END_TIME, "19:00"), CALLBACK_ID);

            assertThat(session.getPendingTemplateEndTime()).isEqualTo(LocalTime.of(19, 0));
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_SLOT_DURATION);
            verify(telegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("❌ время конца совпадает с началом → ошибка, состояние не меняется")
        void endTimeSelected_sameAsStart_showsError() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_END_TIME, "09:00"), CALLBACK_ID);

            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_END_TIME);
            assertThat(session.getPendingTemplateEndTime()).isNull();
            // AnswerCallbackQuery с сообщением об ошибке
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
        }

        @Test
        @DisplayName("❌ время конца раньше начала → ошибка")
        void endTimeSelected_beforeStart_showsError() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_END_TIME, "08:00"), CALLBACK_ID);

            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_END_TIME);
            assertThat(session.getPendingTemplateEndTime()).isNull();
        }
    }

    // handleCallback — выбор длительности слота (шаг 5)

    @Nested
    @DisplayName("handleCallback — длительность слота")
    class HandleCallbackDuration {

        @BeforeEach
        void setDurationState() {
            session.setState(SessionState.AWAITING_TEMPLATE_SLOT_DURATION);
            session.setPendingTemplateName("Тестовый");
            session.setPendingTemplateWorkingDays(Set.of(1, 2, 3, 4, 5));
            session.setPendingTemplateStartTime(LocalTime.of(9, 0));
            session.setPendingTemplateEndTime(LocalTime.of(19, 0));
        }

        @Test
        @DisplayName("✅ выбор 2 часа → сохраняется, показывается сводка")
        void durationSelected_2hours_showsSummary() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_DURATION, 2), CALLBACK_ID);

            assertThat(session.getPendingTemplateSlotDuration()).isEqualTo(2);
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_CONFIRM);
            verify(telegramClient).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("✅ выбор 1 час — допустимо")
        void durationSelected_1hour_allowed() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.build(CallbackData.TEMPLATE_DURATION, 1), CALLBACK_ID);

            assertThat(session.getPendingTemplateSlotDuration()).isEqualTo(1);
            assertThat(session.getState()).isEqualTo(SessionState.AWAITING_TEMPLATE_CONFIRM);
        }
    }

    // handleCallback — подтверждение (шаг 6)

    @Nested
    @DisplayName("handleCallback — подтверждение")
    class HandleCallbackConfirm {

        @BeforeEach
        void setConfirmState() {
            session.setState(SessionState.AWAITING_TEMPLATE_CONFIRM);
            session.setPendingTemplateName("Высокий сезон");
            session.setPendingTemplateWorkingDays(Set.of(1, 2, 3, 4, 5, 6));
            session.setPendingTemplateStartTime(LocalTime.of(9, 0));
            session.setPendingTemplateEndTime(LocalTime.of(20, 0));
            session.setPendingTemplateSlotDuration(2);
        }

        @Test
        @DisplayName("✅ подтверждение → createTemplate вызывается с правильными данными")
        void confirm_callsCreateTemplateWithCorrectData() throws TelegramApiException {
            ScheduleTemplate savedTemplate = ScheduleTemplate.builder()
                    .id(5L).name("Высокий сезон").active(false).slotDurationHours(2).build();
            when(scheduleService.createTemplate(any(), anyInt(), any(), any(), any()))
                    .thenReturn(savedTemplate);

            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_CONFIRM, CALLBACK_ID);

            verify(scheduleService).createTemplate(
                    eq("Высокий сезон"),
                    eq(2),
                    eq(Set.of(1, 2, 3, 4, 5, 6)),
                    eq(LocalTime.of(9, 0)),
                    eq(LocalTime.of(20, 0))
            );

            // Сессия сброшена
            assertThat(session.getState()).isEqualTo(SessionState.NONE);
            assertThat(session.getPendingTemplateName()).isNull();
        }

        @Test
        @DisplayName("✅ после подтверждения сессия полностью сбрасывается")
        void confirm_resetsSession() throws TelegramApiException {
            when(scheduleService.createTemplate(any(), anyInt(), any(), any(), any()))
                    .thenReturn(ScheduleTemplate.builder()
                            .id(1L).name("Тест").active(false).slotDurationHours(2).build());

            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_CONFIRM, CALLBACK_ID);

            assertThat(session.getState()).isEqualTo(SessionState.NONE);
            assertThat(session.getPendingTemplateName()).isNull();
            assertThat(session.getPendingTemplateWorkingDays()).isEmpty();
            assertThat(session.getPendingTemplateStartTime()).isNull();
            assertThat(session.getPendingTemplateEndTime()).isNull();
            assertThat(session.getPendingTemplateSlotDuration()).isNull();
        }

        @Test
        @DisplayName("✅ отмена → сессия сбрасывается, createTemplate не вызывается")
        void cancel_resetsSessionWithoutCreating() throws TelegramApiException {
            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_CANCEL_WIZARD, CALLBACK_ID);

            verify(scheduleService, never()).createTemplate(any(), anyInt(), any(), any(), any());
            assertThat(session.getState()).isEqualTo(SessionState.NONE);
        }

        @Test
        @DisplayName("✅ ошибка createTemplate → сессия всё равно сбрасывается")
        void confirm_createTemplateFails_sessionStillReset() throws TelegramApiException {
            when(scheduleService.createTemplate(any(), anyInt(), any(), any(), any()))
                    .thenThrow(new RuntimeException("БД недоступна"));

            wizardHandler.handleCallback(TELEGRAM_ID,
                    CallbackData.TEMPLATE_CONFIRM, CALLBACK_ID);

            // Сессия сброшена даже при ошибке
            assertThat(session.getState()).isEqualTo(SessionState.NONE);
            // Сообщение об ошибке отправлено
            verify(telegramClient, atLeastOnce()).execute(any(SendMessage.class));
        }
    }

    // Статические методы маршрутизации

    @Nested
    @DisplayName("isWizardState и isWizardCallback")
    class RoutingMethods {

        @Test
        @DisplayName("isWizardState — все wizard-состояния распознаются")
        void isWizardState_allWizardStates() {
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_NAME))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_DAYS))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_START_TIME))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_END_TIME))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_SLOT_DURATION))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_TEMPLATE_CONFIRM))
                    .isTrue();
        }

        @Test
        @DisplayName("isWizardState — чужие состояния не распознаются")
        void isWizardState_nonWizardStates() {
            assertThat(TemplateWizardHandler.isWizardState(SessionState.NONE)).isFalse();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_NAME)).isFalse();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_BOOKING_COMMENT))
                    .isFalse();
            assertThat(TemplateWizardHandler.isWizardState(SessionState.AWAITING_REJECT_REASON))
                    .isFalse();
        }

        @Test
        @DisplayName("isWizardCallback — все wizard-callback распознаются")
        void isWizardCallback_allWizardCallbacks() {
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_NEW)).isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_TOGGLE_DAY))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_DAYS_DONE))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_START_TIME))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_END_TIME))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_DURATION))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_CONFIRM))
                    .isTrue();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_CANCEL_WIZARD))
                    .isTrue();
        }

        @Test
        @DisplayName("isWizardCallback — чужие callback не распознаются")
        void isWizardCallback_nonWizardCallbacks() {
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.BOOK_DATE)).isFalse();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.CONFIRM_BOOKING))
                    .isFalse();
            assertThat(TemplateWizardHandler.isWizardCallback(CallbackData.TEMPLATE_ACTIVATE))
                    .isFalse();
        }
    }
}
