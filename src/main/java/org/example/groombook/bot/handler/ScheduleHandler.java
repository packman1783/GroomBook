package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.exception.GroomBookException;
import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.ScheduleTemplate;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.OverrideType;
import org.example.groombook.service.BookingService;
import org.example.groombook.service.ClientService;
import org.example.groombook.service.ScheduleService;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleHandler {

    private final ScheduleService scheduleService;
    private final ClientService clientService;
    private final BookingService bookingService;
    private final TemplateWizardHandler templateWizardHandler;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;

    private static final DateTimeFormatter DATE_INPUT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Публичные команды — вызываются из MasterHandler

    /**
     * /schedule — главное меню управления расписанием
     */
    public void handleScheduleCommand(Long telegramId) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("📋 Шаблоны", CallbackData.SCHEDULE_TEMPLATES)))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🚫 Заблокировать слот", CallbackData.SCHEDULE_BLOCK)))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🏖 Отпуск / нерабочий период", CallbackData.SCHEDULE_VACATION)))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🤝 Договорная запись", CallbackData.SCHEDULE_MANUAL)))
                .build();

        send(telegramId, "Управление расписанием:", keyboard);
    }

    /**
     * /vacation — быстрый вход в блокировку периода (минуя меню)
     */
    public void handleVacationCommand(Long telegramId) {
        startVacation(telegramId, null);
    }

    /**
     * /manual — быстрый вход в создание договорной записи (минуя меню)
     */
    public void handleManualCommand(Long telegramId) {
        startManualBooking(telegramId, null);
    }

    // Callback-маршрутизация

    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.SCHEDULE_TEMPLATES -> showTemplates(telegramId, callbackId);
            case CallbackData.SCHEDULE_BLOCK -> startBlockSlot(telegramId, callbackId);
            case CallbackData.SCHEDULE_VACATION -> startVacation(telegramId, callbackId);
            case CallbackData.SCHEDULE_MANUAL -> startManualBooking(telegramId, callbackId);

            case CallbackData.TEMPLATE_ACTIVATE -> startTemplateActivation(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.BLOCK_PICK_SLOT -> finishBlockSlotPick(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.MANUAL_PICK_PET -> finishManualPetPick(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            default -> answerCallback(callbackId, null);
        }
    }

    // Шаблоны: список → выбор → дата активации

    private void showTemplates(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);

        List<ScheduleTemplate> templates = scheduleService.getAllTemplates();

        var builder = InlineKeyboardMarkup.builder();

        if (templates.isEmpty()) {
            send(telegramId,
                    "Шаблонов расписания пока нет.\n\n" +
                            "Нажмите кнопку ниже чтобы создать первый шаблон через удобный диалог:",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(
                                    btn("➕ Создать шаблон", CallbackData.TEMPLATE_NEW)))
                            .build());
            return;
        }

        // Список существующих шаблонов
        for (ScheduleTemplate t : templates) {
            String label = (t.isActive() ? "✅ " : "") +
                    t.getName() + " (" + t.getSlotDurationHours() + "ч)";
            builder.keyboardRow(new InlineKeyboardRow(
                    btn(label, CallbackData.build(CallbackData.TEMPLATE_ACTIVATE, t.getId()))));
        }

        // Кнопка создания нового шаблона — всегда внизу списка
        builder.keyboardRow(new InlineKeyboardRow(
                btn("➕ Создать новый шаблон", CallbackData.TEMPLATE_NEW)));

        send(telegramId,
                "Выберите шаблон для активации или создайте новый:\n" +
                        "_(✅ — активный шаблон)_",
                builder.build());
    }

    private void startTemplateActivation(Long telegramId, Long templateId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingClientId(templateId);
        session.setState(SessionState.AWAITING_TEMPLATE_ACTIVATE_DATE);
        send(telegramId,
                "С какой даты активировать шаблон?\n" +
                        "Формат: дд.мм.гггг (или отправьте \"сегодня\")");
    }

    private void finishTemplateActivation(Long telegramId, String text) {
        UserSession session = sessionManager.get(telegramId);
        Long templateId = session.getPendingClientId();

        LocalDate from = text.equalsIgnoreCase("сегодня")
                ? LocalDate.now()
                : parseDateOrNull(text);

        if (from == null) {
            send(telegramId, "Не получилось распознать дату. Используйте формат дд.мм.гггг.");
            return;
        }

        try {
            scheduleService.activateTemplate(templateId, from, null);
            send(telegramId, "✅ Шаблон активирован с " + from.format(DATE_FMT) +
                    ". Свободные слоты пересчитаны.");
        } catch (GroomBookException e) {
            send(telegramId, "Не удалось активировать шаблон: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    // Блокировка слота

    private void startBlockSlot(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_BLOCK_DATE);
        send(telegramId, "На какую дату заблокировать слот?\nФормат: дд.мм.гггг");
    }

    private void handleBlockDateEntered(Long telegramId, String text) {
        LocalDate date = parseDateOrNull(text);
        if (date == null) {
            send(telegramId, "Не получилось распознать дату. Используйте формат дд.мм.гггг.");
            return;
        }

        List<TimeSlot> slots = scheduleService.getAllSlotsForMaster(date);
        if (slots.isEmpty()) {
            send(telegramId, "На эту дату слотов нет (выходной по расписанию).");
            sessionManager.get(telegramId).reset();
            return;
        }

        sessionManager.get(telegramId).setPendingBlockDate(date);

        var rows = slots.stream()
                .filter(TimeSlot::isFree)
                .map(s -> new InlineKeyboardRow(btn(
                        s.getStartTime().format(TIME_FMT) + "–" + s.getEndTime().format(TIME_FMT),
                        CallbackData.build(CallbackData.BLOCK_PICK_SLOT, s.getId()))))
                .toList();

        if (rows.isEmpty()) {
            send(telegramId, "На эту дату нет свободных слотов для блокировки.");
            sessionManager.get(telegramId).reset();
            return;
        }

        send(telegramId, "Выберите слот для блокировки:",
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void finishBlockSlotPick(Long telegramId, Long slotId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBlockSlotId(slotId);
        session.setState(SessionState.AWAITING_BLOCK_REASON);
        send(telegramId, "Укажите причину блокировки (видна только вам):");
    }

    private void handleBlockReasonEntered(Long telegramId, String reason) {
        UserSession session = sessionManager.get(telegramId);
        try {
            scheduleService.blockSlot(session.getPendingBlockSlotId(), reason);
            send(telegramId, "🚫 Слот заблокирован. Причина: " + reason);
        } catch (GroomBookException e) {
            send(telegramId, "Не удалось заблокировать слот: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    // Отпуск / нерабочий период

    private void startVacation(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_VACATION_RANGE);
        send(telegramId,
                "Укажите период в формате: дд.мм.гггг-дд.мм.гггг\n" +
                        "Например: 10.07.2025-20.07.2025");
    }

    private void handleVacationRangeEntered(Long telegramId, String text) {
        String[] parts = text.split("-");
        if (parts.length != 2) {
            send(telegramId, "Неверный формат. Используйте: дд.мм.гггг-дд.мм.гггг");
            return;
        }

        LocalDate from = parseDateOrNull(parts[0].trim());
        LocalDate to = parseDateOrNull(parts[1].trim());

        if (from == null || to == null || to.isBefore(from)) {
            send(telegramId, "Не получилось распознать период. Проверьте даты и порядок.");
            return;
        }

        scheduleService.blockDateRange(from, to, OverrideType.VACATION, "Отпуск");

        List<Booking> affected = bookingService.getActiveBookingsInRange(from, to);

        StringBuilder report = new StringBuilder();
        report.append("🏖 Период ")
                .append(from.format(DATE_FMT)).append(" — ").append(to.format(DATE_FMT))
                .append(" заблокирован.\n\n");

        if (affected.isEmpty()) {
            report.append("На этот период активных записей не было.");
        } else {
            report.append("⚠️ В этот период попадают записи — нужно решить судьбу:\n");
            for (Booking b : affected) {
                report.append(String.format("• %s в %s — %s (%s)\n",
                        b.getSlot().getDate().format(DATE_FMT),
                        b.getSlot().getStartTime().format(TIME_FMT),
                        b.getClient().getName(),
                        b.getClient().getPhone()));
            }
            report.append("\nСвяжитесь с клиентами и отмените или перенесите записи вручную.");
        }

        send(telegramId, report.toString());
        sessionManager.get(telegramId).reset();
    }

    // Договорная запись

    private void startManualBooking(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_MANUAL_CLIENT_PHONE);
        send(telegramId, "Введите номер телефона клиента:");
    }

    private void handleManualClientPhoneEntered(Long telegramId, String phone) {
        Optional<org.example.groombook.model.Client> clientOpt =
                clientService.findByPhone(phone.trim());

        if (clientOpt.isEmpty()) {
            send(telegramId,
                    "Клиент с таким номером не найден.\n" +
                            "Проверьте номер и попробуйте снова, либо отмените через /schedule.");
            return;
        }

        Client client = clientOpt.get();
        List<Pet> pets = clientService.getAllPets(client.getId());

        if (pets.isEmpty()) {
            send(telegramId, "У клиента " + client.getName() + " нет добавленных питомцев.");
            sessionManager.get(telegramId).reset();
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        session.setPendingManualClientId(client.getId());

        var rows = pets.stream()
                .map(p -> new InlineKeyboardRow(btn(p.getName(),
                        CallbackData.build(CallbackData.MANUAL_PICK_PET, p.getId()))))
                .toList();

        send(telegramId,
                "Клиент: " + client.getName() + ". Выберите питомца:",
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void finishManualPetPick(Long telegramId, Long petId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingManualPetId(petId);
        session.setState(SessionState.AWAITING_MANUAL_BOOKING_DATE);
        send(telegramId, "На какую дату запись?\nФормат: дд.мм.гггг");
    }

    private void handleManualDateEntered(Long telegramId, String text) {
        LocalDate date = parseDateOrNull(text);
        if (date == null) {
            send(telegramId, "Не получилось распознать дату. Используйте формат дд.мм.гггг.");
            return;
        }
        UserSession session = sessionManager.get(telegramId);
        session.setPendingManualDate(date);
        session.setState(SessionState.AWAITING_MANUAL_BOOKING_TIME);
        send(telegramId, "Во сколько начало? Формат: ЧЧ:мм (например 14:30).");
    }

    private void handleManualTimeEntered(Long telegramId, String text) {
        LocalTime time;
        try {
            time = LocalTime.parse(text.trim(), TIME_FMT);
        } catch (DateTimeParseException e) {
            send(telegramId, "Не получилось распознать время. Используйте формат ЧЧ:мм.");
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        session.setPendingClientId((long) timeToMinutes(time));
        session.setState(SessionState.AWAITING_MANUAL_COMMENT);
        send(telegramId, "Комментарий к записи (или \"-\" чтобы пропустить):");
    }

    private void handleManualCommentEntered(Long telegramId, String text) {
        UserSession session = sessionManager.get(telegramId);
        String comment = text.equals("-") ? null : text;
        LocalTime time = minutesToTime(session.getPendingClientId().intValue());

        try {
            bookingService.createManualBooking(
                    session.getPendingManualClientId(),
                    session.getPendingManualPetId(),
                    session.getPendingManualDate(),
                    time, 2, comment);

            send(telegramId,
                    "✅ Договорная запись создана на " +
                            session.getPendingManualDate().format(DATE_FMT) +
                            " в " + time.format(TIME_FMT) +
                            ".\nКлиент не уведомляется — вы уже договорились лично.");
        } catch (GroomBookException e) {
            send(telegramId, "Не удалось создать запись: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    // Маршрутизация текстового ввода по состояниям

    public boolean handleTextInput(Long telegramId, String text, SessionState state) {
        switch (state) {
            case AWAITING_TEMPLATE_ACTIVATE_DATE -> finishTemplateActivation(telegramId, text);
            case AWAITING_BLOCK_DATE -> handleBlockDateEntered(telegramId, text);
            case AWAITING_BLOCK_REASON -> handleBlockReasonEntered(telegramId, text);
            case AWAITING_VACATION_RANGE -> handleVacationRangeEntered(telegramId, text);
            case AWAITING_MANUAL_CLIENT_PHONE -> handleManualClientPhoneEntered(telegramId, text);
            case AWAITING_MANUAL_BOOKING_DATE -> handleManualDateEntered(telegramId, text);
            case AWAITING_MANUAL_BOOKING_TIME -> handleManualTimeEntered(telegramId, text);
            case AWAITING_MANUAL_COMMENT -> handleManualCommentEntered(telegramId, text);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Состояния относящиеся к ScheduleHandler — используется MasterHandler для маршрутизации
     */
    public static boolean isScheduleState(SessionState state) {
        return switch (state) {
            case AWAITING_TEMPLATE_ACTIVATE_DATE,
                 AWAITING_BLOCK_DATE,
                 AWAITING_BLOCK_REASON,
                 AWAITING_VACATION_RANGE,
                 AWAITING_MANUAL_CLIENT_PHONE,
                 AWAITING_MANUAL_BOOKING_DATE,
                 AWAITING_MANUAL_BOOKING_TIME,
                 AWAITING_MANUAL_COMMENT -> true;
            default -> false;
        };
    }


    // Вспомогательные методы

    private LocalDate parseDateOrNull(String text) {
        try {
            return LocalDate.parse(text.trim(), DATE_INPUT_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private int timeToMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private LocalTime minutesToTime(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private void send(Long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения мастеру chatId={}: {}", chatId, e.getMessage());
        }
    }

    private void answerCallback(String callbackId, String text) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text(text)
                .build();
        try {
            telegramClient.execute(answer);
        } catch (TelegramApiException e) {
            log.warn("Не удалось ответить на callback {}: {}", callbackId, e.getMessage());
        }
    }
}
