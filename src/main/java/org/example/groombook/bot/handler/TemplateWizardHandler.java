package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.model.ScheduleTemplate;
import org.example.groombook.service.ScheduleService;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

/**
 * Wizard пошагового создания шаблона расписания через Telegram-диалог.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateWizardHandler {

    private final ScheduleService scheduleService;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] DAY_NAMES = {"", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    // --- Точка входа ---

    public void startWizard(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);
        session.reset();
        session.setPendingTemplateWorkingDays(new HashSet<>());
        session.setState(SessionState.AWAITING_TEMPLATE_NAME);

        send(telegramId, """
                📋 Создание шаблона расписания

                Шаг 1 из 5 — Название

                Введите название шаблона, например:
                Стандартный, Высокий сезон, Зима""");
    }

    // --- Текстовый ввод ---

    public void handleTextInput(Long telegramId, String text) {
        UserSession session = sessionManager.get(telegramId);
        if (session.getState() != SessionState.AWAITING_TEMPLATE_NAME) return;

        if (text.isBlank()) {
            send(telegramId, "Название не может быть пустым. Попробуйте ещё раз:");
            return;
        }

        session.setPendingTemplateName(text.trim());
        session.setState(SessionState.AWAITING_TEMPLATE_DAYS);
        askForDays(telegramId);
    }

    // --- Callback-маршрутизация ---

    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.TEMPLATE_TOGGLE_DAY -> handleToggleDay(
                    telegramId, Integer.parseInt(CallbackData.payload(data)), callbackId);
            case CallbackData.TEMPLATE_DAYS_DONE -> handleDaysDone(telegramId, callbackId);
            case CallbackData.TEMPLATE_START_TIME -> handleStartTimeSelected(
                    telegramId, CallbackData.payload(data), callbackId);
            case CallbackData.TEMPLATE_END_TIME -> handleEndTimeSelected(
                    telegramId, CallbackData.payload(data), callbackId);
            case CallbackData.TEMPLATE_DURATION -> handleDurationSelected(
                    telegramId, Integer.parseInt(CallbackData.payload(data)), callbackId);
            case CallbackData.TEMPLATE_CONFIRM -> handleConfirm(telegramId, callbackId);
            case CallbackData.TEMPLATE_CANCEL_WIZARD -> handleCancel(telegramId, callbackId);
            default -> answerCallback(callbackId, null);
        }
    }

    // --- Шаг 2: Рабочие дни ---

    private void askForDays(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);
        try {
            Message sent = telegramClient.execute(SendMessage.builder()
                    .chatId(telegramId.toString())
                    .text("""
                            Шаг 2 из 5 — Рабочие дни

                            Нажмите на день чтобы выбрать или снять. Затем нажмите Готово.""")
                    .replyMarkup(buildDaysKeyboard(session))
                    .build());

            session.setPendingTemplateMessageId(sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки клавиатуры дней: {}", e.getMessage());
        }
    }

    private void handleToggleDay(Long telegramId, int day, String callbackId) {
        UserSession session = sessionManager.get(telegramId);
        if (session.getState() != SessionState.AWAITING_TEMPLATE_DAYS) {
            answerCallback(callbackId, null);
            return;
        }

        if (session.getPendingTemplateWorkingDays().contains(day)) {
            session.getPendingTemplateWorkingDays().remove(day);
        } else {
            session.getPendingTemplateWorkingDays().add(day);
        }

        try {
            telegramClient.execute(EditMessageReplyMarkup.builder()
                    .chatId(telegramId.toString())
                    .messageId(session.getPendingTemplateMessageId())
                    .replyMarkup(buildDaysKeyboard(session))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Не удалось обновить клавиатуру дней: {}", e.getMessage());
        }

        answerCallback(callbackId, null);
    }

    private void handleDaysDone(Long telegramId, String callbackId) {
        UserSession session = sessionManager.get(telegramId);

        if (session.getPendingTemplateWorkingDays().isEmpty()) {
            answerCallback(callbackId, "Выберите хотя бы один рабочий день!");
            return;
        }

        answerCallback(callbackId, null);
        session.setState(SessionState.AWAITING_TEMPLATE_START_TIME);
        askForStartTime(telegramId);
    }

    // --- Шаг 3: Время начала ---

    private void askForStartTime(Long telegramId) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        timeBtn("08:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("09:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("10:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("11:00", CallbackData.TEMPLATE_START_TIME)
                ))
                .build();

        send(telegramId, """
                Шаг 3 из 5 — Начало рабочего дня

                Во сколько начинается рабочий день?""", keyboard);
    }

    private void handleStartTimeSelected(Long telegramId, String timeStr, String callbackId) {
        answerCallback(callbackId, null);

        LocalTime time = LocalTime.parse(timeStr, TIME_FMT);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingTemplateStartTime(time);
        session.setState(SessionState.AWAITING_TEMPLATE_END_TIME);
        askForEndTime(telegramId);
    }

    // --- Шаг 4: Время окончания ---

    private void askForEndTime(Long telegramId) {
        LocalTime start = sessionManager.get(telegramId).getPendingTemplateStartTime();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        timeBtn("17:00", CallbackData.TEMPLATE_END_TIME),
                        timeBtn("18:00", CallbackData.TEMPLATE_END_TIME),
                        timeBtn("19:00", CallbackData.TEMPLATE_END_TIME),
                        timeBtn("20:00", CallbackData.TEMPLATE_END_TIME),
                        timeBtn("21:00", CallbackData.TEMPLATE_END_TIME)
                ))
                .build();

        send(telegramId, String.format("""
                Шаг 4 из 5 — Конец рабочего дня

                Начало: %s
                Во сколько заканчивается рабочий день?""", start.format(TIME_FMT)), keyboard);
    }

    private void handleEndTimeSelected(Long telegramId, String timeStr, String callbackId) {
        LocalTime time = LocalTime.parse(timeStr, TIME_FMT);
        UserSession session = sessionManager.get(telegramId);

        if (!time.isAfter(session.getPendingTemplateStartTime())) {
            answerCallback(callbackId, "Время окончания должно быть позже начала!");
            return;
        }

        answerCallback(callbackId, null);
        session.setPendingTemplateEndTime(time);
        session.setState(SessionState.AWAITING_TEMPLATE_SLOT_DURATION);
        askForDuration(telegramId);
    }

    // --- Шаг 5: Длительность слота ---

    private void askForDuration(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);
        LocalTime start = session.getPendingTemplateStartTime();
        LocalTime end = session.getPendingTemplateEndTime();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("1 час", CallbackData.build(CallbackData.TEMPLATE_DURATION, 1)),
                        btn("2 часа", CallbackData.build(CallbackData.TEMPLATE_DURATION, 2)),
                        btn("3 часа", CallbackData.build(CallbackData.TEMPLATE_DURATION, 3))
                ))
                .build();

        send(telegramId, String.format("""
                Шаг 5 из 5 — Длительность одного слота

                Время работы: %s – %s
                Сколько времени занимает одна стрижка?""", start.format(TIME_FMT), end.format(TIME_FMT)), keyboard);
    }

    private void handleDurationSelected(Long telegramId, int hours, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        session.setPendingTemplateSlotDuration(hours);
        session.setState(SessionState.AWAITING_TEMPLATE_CONFIRM);
        showConfirmation(telegramId);
    }

    // --- Сводка и создание ---

    private void showConfirmation(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);

        String workingDaysStr = session.getPendingTemplateWorkingDays().stream()
                .sorted()
                .map(d -> DAY_NAMES[d])
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");

        long totalMinutes = Duration.between(
                session.getPendingTemplateStartTime(),
                session.getPendingTemplateEndTime()).toMinutes();
        int slotsPerDay = (int) (totalMinutes / (session.getPendingTemplateSlotDuration() * 60));

        String summary = String.format("""
                        📋 Новый шаблон расписания

                        Название: %s
                        Рабочие дни: %s
                        Время работы: %s – %s
                        Длительность слота: %d ч.
                        Слотов в рабочий день: %d

                        Всё верно?""",
                session.getPendingTemplateName(),
                workingDaysStr,
                session.getPendingTemplateStartTime().format(TIME_FMT),
                session.getPendingTemplateEndTime().format(TIME_FMT),
                session.getPendingTemplateSlotDuration(),
                slotsPerDay);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("✅ Создать", CallbackData.TEMPLATE_CONFIRM),
                        btn("❌ Отмена", CallbackData.TEMPLATE_CANCEL_WIZARD)
                ))
                .build();

        send(telegramId, summary, keyboard);
    }

    private void handleConfirm(Long telegramId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);

        try {
            ScheduleTemplate template = scheduleService.createTemplate(
                    session.getPendingTemplateName(),
                    session.getPendingTemplateSlotDuration(),
                    session.getPendingTemplateWorkingDays(),
                    session.getPendingTemplateStartTime(),
                    session.getPendingTemplateEndTime());

            send(telegramId, "✅ Шаблон \"" + template.getName() + "\" создан!\n\n" +
                    "Шаблон пока неактивен. Чтобы активировать — отправьте /schedule → " +
                    "Шаблоны и выберите его из списка.");

        } catch (Exception e) {
            log.error("Ошибка создания шаблона для мастера {}: {}", telegramId, e.getMessage());
            send(telegramId, "Не удалось создать шаблон: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    private void handleCancel(Long telegramId, String callbackId) {
        answerCallback(callbackId, null);
        sessionManager.get(telegramId).reset();
        send(telegramId, "Создание шаблона отменено.");
    }

    // --- Маршрутизация состояния ---

    public static boolean isWizardState(SessionState state) {
        return switch (state) {
            case AWAITING_TEMPLATE_NAME,
                 AWAITING_TEMPLATE_DAYS,
                 AWAITING_TEMPLATE_START_TIME,
                 AWAITING_TEMPLATE_END_TIME,
                 AWAITING_TEMPLATE_SLOT_DURATION,
                 AWAITING_TEMPLATE_CONFIRM -> true;
            default -> false;
        };
    }

    public static boolean isWizardCallback(String prefix) {
        return switch (prefix) {
            case CallbackData.TEMPLATE_NEW,
                 CallbackData.TEMPLATE_TOGGLE_DAY,
                 CallbackData.TEMPLATE_DAYS_DONE,
                 CallbackData.TEMPLATE_START_TIME,
                 CallbackData.TEMPLATE_END_TIME,
                 CallbackData.TEMPLATE_DURATION,
                 CallbackData.TEMPLATE_CONFIRM,
                 CallbackData.TEMPLATE_CANCEL_WIZARD -> true;
            default -> false;
        };
    }

    // --- Построение клавиатур ---

    private InlineKeyboardMarkup buildDaysKeyboard(UserSession session) {
        var builder = InlineKeyboardMarkup.builder();

        for (int dow = 1; dow <= 7; dow++) {
            boolean selected = session.getPendingTemplateWorkingDays() != null
                    && session.getPendingTemplateWorkingDays().contains(dow);
            String label = (selected ? "✅ " : "☐ ") + DAY_NAMES[dow];
            builder.keyboardRow(new InlineKeyboardRow(
                    btn(label, CallbackData.build(CallbackData.TEMPLATE_TOGGLE_DAY, dow))
            ));
        }

        builder.keyboardRow(new InlineKeyboardRow(
                btn("➡️ Готово", CallbackData.TEMPLATE_DAYS_DONE)
        ));

        return builder.build();
    }

    // --- Вспомогательные методы ---

    private InlineKeyboardButton timeBtn(String time, String prefix) {
        return btn(time, CallbackData.build(prefix, time));
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private void send(Long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки wizard-сообщения chatId={}: {}", chatId, e.getMessage());
        }
    }

    private void answerCallback(String callbackId, String text) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Ошибка ответа на callback {}: {}", callbackId, e.getMessage());
        }
    }
}
