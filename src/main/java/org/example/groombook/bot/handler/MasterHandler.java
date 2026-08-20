package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.exception.GroomBookException;
import org.example.groombook.model.Booking;
import org.example.groombook.model.enums.ClientStatus;
import org.example.groombook.service.BookingService;
import org.example.groombook.service.ClientService;
import org.example.groombook.service.NotificationService;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Главный хэндлер управления бизнес-процессами мастера.
 * <p>
 * Отвечает за:
 * <ul>
 *   <li>Просмотр записи на выбранные дни (/today, /tomorrow, /week, /fortnight)</li>
 *   <li>Управление жизненным циклом бронирования (подтверждение, отклонение, завершение, неявка)</li>
 *   <li>Делегирование задач управления расписанием и шаблонами подклассам {@link ScheduleHandler} и {@link TemplateWizardHandler}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterHandler {
    private final ClientService clientService;
    private final BookingService bookingService;
    private final NotificationService notificationService;
    private final ScheduleHandler scheduleHandler;
    private final TemplateWizardHandler templateWizardHandler;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // --- Обработка команд ---

    /**
     * Обработка сервисных и административных команд мастера.
     */
    public void handleCommand(Long telegramId, String text) {
        String command = text.split("\\s+")[0].toLowerCase();

        switch (command) {
            case "/start" -> showWelcome(telegramId);
            case "/today" -> showBookingsForDate(telegramId, LocalDate.now());
            case "/tomorrow" -> showBookingsForDate(telegramId, LocalDate.now().plusDays(1));
            case "/week" -> showBookingsForPeriod(telegramId, "неделю", LocalDate.now(), LocalDate.now().plusDays(6));
            case "/fortnight" -> showBookingsForPeriod(telegramId, "2 недели", LocalDate.now(), LocalDate.now().plusDays(13));
            case "/schedule" -> scheduleHandler.handleScheduleCommand(telegramId);
            case "/vacation" -> scheduleHandler.handleVacationCommand(telegramId);
            case "/manual" -> scheduleHandler.handleManualCommand(telegramId);
            case "/newtemplate" -> templateWizardHandler.startWizard(telegramId);
            case "/blocked" -> showBlockedClients(telegramId);
            case "/help" -> handleHelp(telegramId);
            default -> send(telegramId, "Неизвестная команда. Введите /help для просмотра списка команд.");
        }
    }

    private void showWelcome(Long telegramId) {
        send(telegramId, "Здравствуйте, Мастер! 👋 Я ваш помощник по записи. Используйте /help для просмотра всех возможностей.");
    }

    /**
     * Выводит справку по административным командам для мастера.
     */
    private void handleHelp(Long telegramId) {
        String helpText = """
                🛠 *Панель управления Мастера* 🛠
                
                *Просмотр записей:*
                /today — Список всех записей на сегодня
                /tomorrow — Список всех записей на завтра
                /week — Список записей на ближайшие 7 дней
                /fortnight — Список записей на ближайшие 14 дней

                *Управление расписанием:*
                /schedule — Общее управление слотами и шаблонами
                /newtemplate — Создать новый недельный график работы
                /vacation — Заблокировать диапазон дат (отпуск/выходные)
                /manual — Записать клиента вручную (через выбор из базы)
                /blocked — Список заблокированных клиентов
                
                *Совет:* Для подтверждения или отмены записи используйте кнопки под сообщениями о новых заявках.
                """;
        send(telegramId, helpText);
    }

    /**
     * Обрабатывает нераспознанные текстовые команды для мастера.
     */
    public void handleUnrecognizedText(Long telegramId) {
        send(telegramId, "Не понимаю это сообщение. Используйте /help для просмотра списка команд.");
    }

    // --- Просмотр записей ---

    /**
     * Отображает список визитов на конкретную дату с кнопками быстрого управления каждой бронью.
     */
    private void showBookingsForDate(Long telegramId, LocalDate date) {
        showBookingsForPeriod(telegramId, date.format(DATE_FMT), date, date);
    }

    /**
     * Отображает список активных визитов за указанный период.
     */
    private void showBookingsForPeriod(Long telegramId, String periodTitle, LocalDate from, LocalDate to) {
        List<Booking> bookings = bookingService.getActiveBookingsInRange(from, to);

        if (bookings.isEmpty()) {
            send(telegramId, "За период (" + periodTitle + ") записей нет.");
            return;
        }

        send(telegramId, "📅 Записи на " + periodTitle + ":");

        for (Booking booking : bookings) {
            String statusLabel = booking.isConfirmed() ? "✅ подтверждена" : "⏳ ожидает подтверждения";

            String msgText = String.format("""
                            [%s] %s–%s
                            👤 %s, %s
                            🐾 %s
                            Статус: %s
                            %s""",
                    booking.getSlot().getDate().format(DATE_FMT),
                    booking.getSlot().getStartTime().format(TIME_FMT),
                    booking.getSlot().getEndTime().format(TIME_FMT),
                    booking.getClient().getName(),
                    booking.getClient().getPhone(),
                    booking.getPet().getName(),
                    statusLabel,
                    booking.getClientComment() != null ? "💬 " + booking.getClientComment() : ""
            );

            send(telegramId, msgText, buildDayKeyboard(booking.getId(), booking.isConfirmed()));
        }
    }

    // --- Обработка callbacks ---

    /**
     * Диспетчеризация callback-событий мастера.
     * Маршрутизирует специфичные запросы в мастера шаблонов или расписания при необходимости.
     */
    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        // Проверка: относится ли callback к мастеру создания шаблонов
        if (TemplateWizardHandler.isWizardCallback(prefix)) {
            templateWizardHandler.handleCallback(telegramId, data, callbackId);
            return;
        }

        switch (prefix) {
            case CallbackData.CONFIRM_BOOKING -> handleConfirm(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.REJECT_BOOKING -> handleRejectRequest(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.COMPLETE_BOOKING -> handleCompleteRequest(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.NO_SHOW_BOOKING -> handleNoShow(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.BLOCK_CLIENT -> handleBlockClientRequest(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.UNBLOCK_CLIENT -> handleUnblockClient(telegramId, CallbackData.payloadAsLong(data), callbackId);

            // Перенаправление события в хэндлер расписания
            case CallbackData.SCHEDULE_TEMPLATES,
                 CallbackData.SCHEDULE_BLOCK,
                 CallbackData.SCHEDULE_VACATION,
                 CallbackData.SCHEDULE_MANUAL,
                 CallbackData.TEMPLATE_ACTIVATE,
                 CallbackData.BLOCK_PICK_SLOT,
                 CallbackData.MANUAL_PICK_PET -> scheduleHandler.handleCallback(telegramId, data, callbackId);

            default -> answerCallback(callbackId, null);
        }
    }

    // --- Обработка ввода текста ---

    /**
     * Принимает текстовый ввод мастера во время выполнения диалоговых сценариев.
     */
    public void handleTextInput(Long telegramId, String text, SessionState state) {
        if (TemplateWizardHandler.isWizardState(state)) {
            templateWizardHandler.handleTextInput(telegramId, text);
            return;
        }

        if (ScheduleHandler.isScheduleState(state)) {
            scheduleHandler.handleTextInput(telegramId, text, state);
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        Long bookingId = session.getPendingBookingId();

        switch (state) {
            case AWAITING_REJECT_REASON -> {
                try {
                    bookingService.rejectBooking(bookingId, text);
                    send(telegramId, "Заявка отклонена. Клиент уведомлён.");
                } catch (GroomBookException e) {
                    send(telegramId, "Не удалось отклонить: " + e.getMessage());
                } finally {
                    session.reset();
                }
            }
            case AWAITING_MASTER_NOTE -> {
                try {
                    String note = "-".equals(text) ? null : text;
                    bookingService.completeBooking(bookingId, note);
                    send(telegramId, "Визит отмечен как завершённый.");
                } catch (GroomBookException e) {
                    send(telegramId, "Не удалось завершить: " + e.getMessage());
                } finally {
                    session.reset();
                }
            }
            case AWAITING_BLOCK_REASON -> {
                Long clientId = session.getPendingClientId();
                try {
                    clientService.changeClientStatus(clientId, ClientStatus.BLOCKED, text);
                    notificationService.notifyClientBlocked(clientService.getById(clientId).getTelegramId());
                    send(telegramId, "Клиент заблокирован. Он больше не сможет записываться.");
                } catch (GroomBookException e) {
                    send(telegramId, "Не удалось заблокировать: " + e.getMessage());
                } finally {
                    session.reset();
                }
            }
            default -> handleUnrecognizedText(telegramId);
        }
    }

    // --- Действия с бронью ---

    /** Подтверждение заявки на бронирование мастером. */
    private void handleConfirm(Long telegramId, Long bookingId, String callbackId) {
        try {
            bookingService.confirmBooking(bookingId);
            answerCallback(callbackId, "Подтверждено");
            send(telegramId, "✅ Бронь #" + bookingId + " подтверждена. Клиент уведомлён.");
        } catch (GroomBookException e) {
            answerCallback(callbackId, null);
            send(telegramId, "Не удалось подтвердить: " + e.getMessage());
        }
    }

    /** Запрос причины отклонения записи (перевод сессии в ожидание ввода текста). */
    private void handleRejectRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBookingId(bookingId);
        session.setState(SessionState.AWAITING_REJECT_REASON);
        send(telegramId, "Укажите причину отклонения (клиент её не увидит):");
    }

    /** Запрос служебной заметки после успешного оказания услуги. */
    private void handleCompleteRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBookingId(bookingId);
        session.setState(SessionState.AWAITING_MASTER_NOTE);
        send(telegramId, "Заметка о визите (или \"-\" чтобы пропустить):");
    }

    /** Отметка о том, что клиент не явился на процедуру (No-Show). */
    private void handleNoShow(Long telegramId, Long bookingId, String callbackId) {
        try {
            bookingService.markNoShow(bookingId);
            answerCallback(callbackId, "Отмечено");
            send(telegramId, "Отмечено как no-show. Слот освобождён.");
        } catch (GroomBookException e) {
            answerCallback(callbackId, null);
            send(telegramId, "Не удалось отметить: " + e.getMessage());
        }
    }

    /** Запрос причины блокировки клиента. */
    private void handleBlockClientRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        try {
            Booking booking = bookingService.findBookingById(bookingId);
            UserSession session = sessionManager.get(telegramId);
            session.setPendingClientId(booking.getClient().getId());
            session.setState(SessionState.AWAITING_BLOCK_REASON);
            send(telegramId, "Укажите причину блокировки клиента (клиент её не увидит):");
        } catch (GroomBookException e) {
            send(telegramId, "Ошибка: " + e.getMessage());
        }
    }

    /** Просмотр списка заблокированных клиентов. */
    private void showBlockedClients(Long telegramId) {
        List<org.example.groombook.model.Client> blockedClients = clientService.getAllClients().stream()
                .filter(org.example.groombook.model.Client::isBlocked)
                .toList();

        if (blockedClients.isEmpty()) {
            send(telegramId, "Заблокированных клиентов нет.");
            return;
        }

        send(telegramId, "🚫 *Заблокированные клиенты:*");

        for (org.example.groombook.model.Client client : blockedClients) {
            String msgText = String.format("""
                            👤 *%s*
                            📞 %s
                            📝 Причина: %s""",
                    client.getName(),
                    client.getPhone(),
                    client.getStatusReason() != null ? client.getStatusReason() : "не указана"
            );

            InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            btn("🔓 Разблокировать", CallbackData.build(CallbackData.UNBLOCK_CLIENT, client.getId()))
                    ))
                    .build();

            send(telegramId, msgText, kb);
        }
    }

    /** Разблокировка клиента. */
    private void handleUnblockClient(Long telegramId, Long clientId, String callbackId) {
        try {
            org.example.groombook.model.Client client = clientService.getById(clientId);
            clientService.changeClientStatus(clientId, ClientStatus.ACTIVE, null);
            notificationService.notifyClientUnblocked(client.getTelegramId());
            answerCallback(callbackId, "Разблокирован");
            send(telegramId, "✅ Клиент " + client.getName() + " разблокирован.");
        } catch (GroomBookException e) {
            answerCallback(callbackId, null);
            send(telegramId, "Не удалось разблокировать: " + e.getMessage());
        }
    }

    // --- Вспомогательные методы ---

    /**
     * Создает динамическую инлайн-клавиатуру с действиями для конкретной записи
     * в зависимости от её текущего статуса (подтверждена / ожидание).
     */
    private InlineKeyboardMarkup buildDayKeyboard(Long bookingId, boolean confirmed) {
        if (!confirmed) {
            return InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            btn("✅ Подтвердить", CallbackData.build(CallbackData.CONFIRM_BOOKING, bookingId)),
                            btn("❌ Отклонить", CallbackData.build(CallbackData.REJECT_BOOKING, bookingId))
                    ))
                    .keyboardRow(new InlineKeyboardRow(
                            btn("🚫 Блокировать клиента", CallbackData.build(CallbackData.BLOCK_CLIENT, bookingId))
                    ))
                    .build();
        }
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("☑️ Завершить", CallbackData.build(CallbackData.COMPLETE_BOOKING, bookingId)),
                        btn("🚫 No-show", CallbackData.build(CallbackData.NO_SHOW_BOOKING, bookingId))
                ))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🚫 Блокировать клиента", CallbackData.build(CallbackData.BLOCK_CLIENT, bookingId))
                ))
                .build();
    }

    /** Утилитарный метод сборки отдельной инлайн-кнопки. */
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
