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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

/**
 * Wizard пошагового создания шаблона расписания через Telegram-диалог.
 * <p>
 * Шаги:
 * 1. Название (текстовый ввод)
 * 2. Рабочие дни (инлайн-кнопки с тоглами, редактирует одно сообщение)
 * 3. Время начала рабочего дня (кнопки)
 * 4. Время окончания рабочего дня (кнопки)
 * 5. Длительность одного слота (кнопки)
 * 6. Подтверждение с итоговой сводкой (кнопки)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateWizardHandler {

    private final ScheduleService scheduleService;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Названия дней недели: индекс 1=Пн ... 7=Вс
     */
    private static final String[] DAY_NAMES = {"", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    // Точка входа

    /**
     * Вызывается из ScheduleHandler или MasterHandler при запуске wizard-а
     */
    public void startWizard(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);
        session.reset();
        session.setPendingTemplateWorkingDays(new HashSet<>());
        session.setState(SessionState.AWAITING_TEMPLATE_NAME);

        send(telegramId,
                "📋 *Создание шаблона расписания*\n\n" +
                        "Шаг 1 из 5 — Название\n\n" +
                        "Введите название шаблона, например:\n" +
                        "_Стандартный_, _Высокий сезон_, _Зима_");
    }

    // Текстовый ввод (только шаг 1 — название)

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

    // Callback-маршрутизация

    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.TEMPLATE_TOGGLE_DAY -> handleToggleDay(
                    telegramId,
                    Integer.parseInt(CallbackData.payload(data)),
                    callbackId);

            case CallbackData.TEMPLATE_DAYS_DONE -> handleDaysDone(telegramId, callbackId);

            case CallbackData.TEMPLATE_START_TIME -> handleStartTimeSelected(
                    telegramId, CallbackData.payload(data), callbackId);

            case CallbackData.TEMPLATE_END_TIME -> handleEndTimeSelected(
                    telegramId, CallbackData.payload(data), callbackId);

            case CallbackData.TEMPLATE_DURATION -> handleDurationSelected(
                    telegramId,
                    Integer.parseInt(CallbackData.payload(data)),
                    callbackId);

            case CallbackData.TEMPLATE_CONFIRM -> handleConfirm(telegramId, callbackId);
            case CallbackData.TEMPLATE_CANCEL_WIZARD -> handleCancel(telegramId, callbackId);

            default -> answerCallback(callbackId, null);
        }
    }

    // Шаг 2 — выбор рабочих дней (тогл-кнопки)

    private void askForDays(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);
        try {
            Message sent = telegramClient.execute(SendMessage.builder()
                    .chatId(telegramId.toString())
                    .text("Шаг 2 из 5 — Рабочие дни\n\n" +
                            "Нажмите на день чтобы выбрать или снять. " +
                            "Затем нажмите *Готово*.")
                    .parseMode("Markdown")
                    .replyMarkup(buildDaysKeyboard(session))
                    .build());

            // Сохраняем ID сообщения — будем редактировать его при каждом шаге
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

        // Переключаем выбранный день
        if (session.getPendingTemplateWorkingDays().contains(day)) {
            session.getPendingTemplateWorkingDays().remove(day);
        } else {
            session.getPendingTemplateWorkingDays().add(day);
        }

        // Редактируем то же сообщение — обновляем ✅/☐ без создания нового
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

    // Шаг 3 — время начала

    private void askForStartTime(Long telegramId) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        timeBtn("08:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("09:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("10:00", CallbackData.TEMPLATE_START_TIME),
                        timeBtn("11:00", CallbackData.TEMPLATE_START_TIME)
                ))
                .build();

        send(telegramId,
                "Шаг 3 из 5 — Начало рабочего дня\n\n" +
                        "Во сколько начинается рабочий день?",
                keyboard);
    }

    private void handleStartTimeSelected(Long telegramId, String timeStr, String callbackId) {
        answerCallback(callbackId, null);

        LocalTime time = LocalTime.parse(timeStr, TIME_FMT);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingTemplateStartTime(time);
        session.setState(SessionState.AWAITING_TEMPLATE_END_TIME);
        askForEndTime(telegramId);
    }

    // Шаг 4 — время окончания

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

        send(telegramId,
                "Шаг 4 из 5 — Конец рабочего дня\n\n" +
                        "Начало: " + start.format(TIME_FMT) + "\n" +
                        "Во сколько заканчивается рабочий день?",
                keyboard);
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

    // Шаг 5 — длительность слота

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

        send(telegramId,
                "Шаг 5 из 5 — Длительность одного слота\n\n" +
                        "Время работы: " + start.format(TIME_FMT) + " – " + end.format(TIME_FMT) + "\n" +
                        "Сколько времени занимает одна стрижка?",
                keyboard);
    }

    private void handleDurationSelected(Long telegramId, int hours, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        session.setPendingTemplateSlotDuration(hours);
        session.setState(SessionState.AWAITING_TEMPLATE_CONFIRM);
        showConfirmation(telegramId);
    }

    // Итоговая сводка и подтверждение

    private void showConfirmation(Long telegramId) {
        UserSession session = sessionManager.get(telegramId);

        // Рабочие дни — отсортированные названия через запятую
        String workingDaysStr = session.getPendingTemplateWorkingDays().stream()
                .sorted()
                .map(d -> DAY_NAMES[d])
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");

        // Количество слотов в рабочий день
        long totalMinutes = java.time.Duration.between(
                session.getPendingTemplateStartTime(),
                session.getPendingTemplateEndTime()).toMinutes();
        int slotsPerDay = (int) (totalMinutes / (session.getPendingTemplateSlotDuration() * 60));

        String summary = String.format(
                "📋 *Новый шаблон расписания*\n\n" +
                        "Название: *%s*\n" +
                        "Рабочие дни: %s\n" +
                        "Время работы: %s – %s\n" +
                        "Длительность слота: %d ч.\n" +
                        "Слотов в рабочий день: *%d*\n\n" +
                        "Всё верно?",
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

            send(telegramId,
                    "✅ Шаблон *" + escapeMarkdown(template.getName()) + "* создан\\!\n\n" +
                            "Шаблон пока неактивен\\. Чтобы активировать — отправьте /schedule → " +
                            "Шаблоны и выберите его из списка\\.");

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

    // Маршрутизация из MasterHandler

    /**
     * Все состояния которые обрабатывает этот wizard
     */
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

    /**
     * Callback-префиксы которые принадлежат этому wizard-у
     */
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

    // Построение клавиатур

    /**
     * Клавиатура выбора дней — 7 кнопок + "Готово".
     * Каждый клик на день редактирует это же сообщение через EditMessageReplyMarkup.
     */
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

    // Вспомогательные методы

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
                    .parseMode("Markdown")
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

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!])", "\\\\$1");
    }
}
