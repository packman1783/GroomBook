package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.keyboard.InlineKeyboardFactory;
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

/**
 * Хэндлер для гибкой настройки рабочего расписания мастера.
 * <p>
 * Реализует возможности:
 * <ul>
 *   <li>Активации и переключения шаблонов рабочих дней</li>
 *   <li>Ручной точечной блокировки отдельных слотов</li>
 *   <li>Оформления периода отпуска (блокировки интервала дат с проверкой пересечений с записями)</li>
 *   <li>Создания договорных ("ручных") записей в обход стандартных проверок доступности</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleHandler {

    private final ScheduleService scheduleService;
    private final ClientService clientService;
    private final BookingService bookingService;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;
    private final InlineKeyboardFactory keyboards;

    /** Формат ввода дат пользователем (например, "25.12.2026"). */
    private static final DateTimeFormatter DATE_INPUT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // --- Публичные команды ---

    /** Вывод главного меню управления расписанием. */
    public void handleScheduleCommand(Long telegramId) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(btn("📋 Шаблоны", CallbackData.SCHEDULE_TEMPLATES)))
                .keyboardRow(new InlineKeyboardRow(btn("🚫 Заблокировать слот", CallbackData.SCHEDULE_BLOCK)))
                .keyboardRow(new InlineKeyboardRow(btn("🏖 Отпуск / нерабочий период", CallbackData.SCHEDULE_VACATION)))
                .keyboardRow(new InlineKeyboardRow(btn("🤝 Договорная запись", CallbackData.SCHEDULE_MANUAL)))
                .build();

        send(telegramId, "Управление расписанием:", keyboard);
    }

    /** Быстрый запуск сценария оформления отпуска. */
    public void handleVacationCommand(Long telegramId) {
        startVacation(telegramId, null);
    }

    /** Быстрый запуск сценария ручного создания записи мастеру. */
    public void handleManualCommand(Long telegramId) {
        startManualBooking(telegramId, null);
    }

    // --- Callback-маршрутизация ---

    /** Обработка кликов в меню управления расписанием. */
    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.SCHEDULE_TEMPLATES -> showTemplates(telegramId, callbackId);
            case CallbackData.SCHEDULE_BLOCK -> startBlockSlot(telegramId, callbackId);
            case CallbackData.SCHEDULE_VACATION -> startVacation(telegramId, callbackId);
            case CallbackData.SCHEDULE_MANUAL -> startManualBooking(telegramId, callbackId);
            case CallbackData.TEMPLATE_ACTIVATE -> startTemplateActivation(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.BLOCK_PICK_SLOT -> finishBlockSlotPick(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.MANUAL_PICK_PET -> finishManualPetPick(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.MANUAL_BOOKING_SKIP_COMMENT -> handleManualBookingSkipComment(telegramId, callbackId);
            default -> answerCallback(callbackId, null);
        }
    }

    // --- Управление шаблонами ---

    /**
     * Отображает список существующих шаблонов с возможностью активации любого из них.
     */
    private void showTemplates(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);

        List<ScheduleTemplate> templates = scheduleService.getAllTemplates();
        var builder = InlineKeyboardMarkup.builder();

        if (templates.isEmpty()) {
            send(telegramId,
                    """
                            Шаблонов расписания пока нет.
                            
                            Нажмите кнопку ниже чтобы создать первый шаблон через удобный диалог:""",
                    InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(btn("➕ Создать шаблон", CallbackData.TEMPLATE_NEW)))
                            .build());
            return;
        }

        for (ScheduleTemplate t : templates) {
            String label = (t.isActive() ? "✅ " : "") + t.getName() + " (" + t.getSlotDurationHours() + "ч)";
            builder.keyboardRow(new InlineKeyboardRow(btn(label, CallbackData.build(CallbackData.TEMPLATE_ACTIVATE, t.getId()))));
        }

        builder.keyboardRow(new InlineKeyboardRow(btn("➕ Создать новый шаблон", CallbackData.TEMPLATE_NEW)));

        send(telegramId, "Выберите шаблон для активации или создайте новый:\n_(✅ — активный шаблон)_", builder.build());
    }

    /** Запрос даты, с которой должен вступить в силу выбранный шаблон. */
    private void startTemplateActivation(Long telegramId, Long templateId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingTemplateId(templateId);
        session.setState(SessionState.AWAITING_TEMPLATE_ACTIVATE_DATE);
        send(telegramId, "С какой даты активировать шаблон?\nФормат: дд.мм.гггг (или отправьте \"сегодня\")");
    }

    /** Применение шаблона к расписанию с указанной даты. */
    private void finishTemplateActivation(Long telegramId, String text) {
        UserSession session = sessionManager.get(telegramId);
        Long templateId = session.getPendingTemplateId();

        LocalDate from = "сегодня".equalsIgnoreCase(text.trim()) ? LocalDate.now() : parseDateOrNull(text);

        if (from == null) {
            send(telegramId, "Не получилось распознать дату. Используйте формат дд.мм.гггг.");
            return;
        }

        try {
            scheduleService.activateTemplate(templateId, from, null);
            send(telegramId, "✅ Шаблон активирован с " + from.format(DATE_FMT) + ". Свободные слоты пересчитаны.");
        } catch (GroomBookException e) {
            send(telegramId, "Не удалось активировать шаблон: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    // --- Блокировка слотов ---

    /** Запрос даты для поиска слотов, подлежащих блокировке. */
    private void startBlockSlot(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_BLOCK_DATE);
        send(telegramId, "На какую дату заблокировать слот?\nФормат: дд.мм.гггг");
    }

    /** Получение списка доступных слотов на выбранную дату и их вывод в виде инлайн-клавиатуры. */
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

        send(telegramId, "Выберите слот для блокировки:", InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** Переход к запросу причины блокировки выбранного слота. */
    private void finishBlockSlotPick(Long telegramId, Long slotId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBlockSlotId(slotId);
        session.setState(SessionState.AWAITING_BLOCK_REASON);
        send(telegramId, "Укажите причину блокировки (видна только вам):");
    }

    /** Вызов сервиса для непосредственной блокировки слота в БД. */
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

    // --- Отпуск / Блокировка периода ---

    /** Инициализация ввода диапазона дат отпуска. */
    private void startVacation(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_VACATION_RANGE);
        send(telegramId, "Укажите период в формате: дд.мм.гггг-дд.мм.гггг\nНапример: 10.07.2025-20.07.2025");
    }

    /**
     * Валидация периода отпуска, блокировка дней и форматирование отчета с затронутыми записями клиентов.
     */
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

    // --- Договорная запись ---

    /** Старт ручного создания визита: запуск поиска клиента по номеру телефона. */
    private void startManualBooking(Long telegramId, String callbackId) {
        if (callbackId != null) answerCallback(callbackId, null);
        sessionManager.get(telegramId).setState(SessionState.AWAITING_MANUAL_CLIENT_PHONE);
        send(telegramId, "Введите номер телефона клиента:");
    }

    /** Поиск клиента по номеру телефона и отображение его питомцев для выбора. */
    private void handleManualClientPhoneEntered(Long telegramId, String phone) {
        Optional<Client> clientOpt = clientService.findByPhone(phone.trim());

        if (clientOpt.isEmpty()) {
            send(telegramId, "Клиент с таким номером не найден.\n" +
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
                .map(p -> new InlineKeyboardRow(btn(p.getName(), CallbackData.build(CallbackData.MANUAL_PICK_PET, p.getId()))))
                .toList();

        send(telegramId, "Клиент: " + client.getName() + ". Выберите питомца:", InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** Сохранение выбранного питомца и запрос даты записи. */
    private void finishManualPetPick(Long telegramId, Long petId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingManualPetId(petId);
        session.setState(SessionState.AWAITING_MANUAL_BOOKING_DATE);
        send(telegramId, "На какую дату запись?\nФормат: дд.мм.гггг");
    }

    /** Парсинг и сохранение даты договорного визита. */
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

    /** Парсинг и сохранение времени начала договорного визита. */
    private void handleManualTimeEntered(Long telegramId, String text) {
        LocalTime time;
        try {
            time = LocalTime.parse(text.trim(), TIME_FMT);
        } catch (DateTimeParseException e) {
            send(telegramId, "Не получилось распознать время. Используйте формат ЧЧ:мм.");
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        session.setPendingManualTime(time);
        session.setState(SessionState.AWAITING_MANUAL_COMMENT);
        send(telegramId, "Комментарий к записи (или нажмите кнопку ниже, чтобы пропустить):",
                keyboards.manualBookingSkipKeyboard());
    }

    /** Пропуск ввода комментария при ручной записи. */
    private void handleManualBookingSkipComment(Long telegramId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        if (session.getState() == SessionState.AWAITING_MANUAL_COMMENT) {
            handleManualCommentEntered(telegramId, "-");
        }
    }

    /** Финализация создания запись вручную мастером. */
    private void handleManualCommentEntered(Long telegramId, String text) {
        UserSession session = sessionManager.get(telegramId);
        String comment = "-".equals(text) ? null : text;
        LocalTime time = session.getPendingManualTime();

        try {
            bookingService.createManualBooking(
                    session.getPendingManualClientId(),
                    session.getPendingManualPetId(),
                    session.getPendingManualDate(),
                    time, 2, comment);

            send(telegramId, "✅ Договорная запись создана на " +
                    session.getPendingManualDate().format(DATE_FMT) +
                    " в " + time.format(TIME_FMT) +
                    ".\nКлиент не уведомляется — вы уже договорились лично.");
        } catch (GroomBookException e) {
            send(telegramId, "Не удалось создать запись: " + e.getMessage());
        } finally {
            session.reset();
        }
    }

    // --- Маршрутизация ввода текста ---

    /**
     * Маршрутизатор текстовых сообщений для пошаговых мастера настройки расписания.
     */
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
     * Вспомогательный метод для внешней проверки: принадлежит ли состояние сессии к сценариям расписания.
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

    // --- Вспомогательные методы ---

    /** Безопасный парсер даты из формата "дд.мм.гггг".Возвращает null при ошибке. */
    private LocalDate parseDateOrNull(String text) {
        try {
            return LocalDate.parse(text.trim(), DATE_INPUT_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
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
