package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.exception.GroomBookException;
import org.example.groombook.model.Booking;
import org.example.groombook.service.BookingService;

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
 * Обработчик команд мастера.
 * <p>
 * НА ЭТОМ ШАГЕ реализованы ежедневные операции — просмотр брони на день,
 * подтверждение/отклонение/завершение/no-show.
 * <p>
 * Управление расписанием (/schedule — создание и переключение шаблонов,
 * /vacation — отпуск, /manual — договорные записи, блокировка отдельных слотов)
 * добавим отдельным шагом — это самостоятельный по объёму кусок логики.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterHandler {

    private final BookingService bookingService;
    private final SessionManager sessionManager;
    private final TelegramClient telegramClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Команды

    public void handleCommand(Long telegramId, String text) {
        String command = text.split("\\s+")[0].toLowerCase();

        switch (command) {
            case "/today" -> showBookingsForDate(telegramId, LocalDate.now());
            case "/tomorrow" -> showBookingsForDate(telegramId, LocalDate.now().plusDays(1));
            default -> send(telegramId, "Доступные команды: /today, /tomorrow");
        }
    }

    public void handleUnrecognizedText(Long telegramId) {
        send(telegramId, "Не понимаю это сообщение. Используйте /today или /tomorrow.");
    }

    // Просмотр броней на день

    private void showBookingsForDate(Long telegramId, LocalDate date) {
        List<Booking> bookings = bookingService.getBookingsForDate(date);

        if (bookings.isEmpty()) {
            send(telegramId, "На " + date.format(DATE_FMT) + " записей нет.");
            return;
        }

        send(telegramId, "📅 Записи на " + date.format(DATE_FMT) + ":");

        for (Booking booking : bookings) {
            String statusLabel = booking.isConfirmed() ? "✅ подтверждена" : "⏳ ожидает подтверждения";

            String text = String.format("""
                            %s–%s
                            👤 %s, %s
                            🐾 %s
                            Статус: %s
                            %s""",
                    booking.getSlot().getStartTime().format(TIME_FMT),
                    booking.getSlot().getEndTime().format(TIME_FMT),
                    booking.getClient().getName(),
                    booking.getClient().getPhone(),
                    booking.getPet().getName(),
                    statusLabel,
                    booking.getClientComment() != null ? "💬 " + booking.getClientComment() : ""
            );

            send(telegramId, text, completeNoShowKeyboard(booking.getId(), booking.isConfirmed()));
        }
    }

    // Callback-кнопки: подтвердить / отклонить / завершить / no-show

    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.CONFIRM_BOOKING -> handleConfirm(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.REJECT_BOOKING -> handleRejectRequest(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.COMPLETE_BOOKING -> handleCompleteRequest(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.NO_SHOW_BOOKING -> handleNoShow(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            default -> answerCallback(callbackId, null);
        }
    }

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

    private void handleRejectRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBookingId(bookingId);
        session.setState(SessionState.AWAITING_REJECT_REASON);
        send(telegramId, "Укажите причину отклонения (клиент её не увидит):");
    }

    private void handleCompleteRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        UserSession session = sessionManager.get(telegramId);
        session.setPendingBookingId(bookingId);
        session.setState(SessionState.AWAITING_MASTER_NOTE);
        send(telegramId, "Заметка о визите (или \"-\" чтобы пропустить):");
    }

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

    // Текстовый ввод после нажатия "Отклонить" / "Завершить"

    public void handleTextInput(Long telegramId, String text, SessionState state) {
        UserSession session = sessionManager.get(telegramId);
        Long bookingId = session.getPendingBookingId();

        switch (state) {
            case AWAITING_REJECT_REASON -> {
                bookingService.rejectBooking(bookingId, text);
                send(telegramId, "Заявка отклонена. Клиент уведомлён.");
                session.reset();
            }
            case AWAITING_MASTER_NOTE -> {
                String note = text.equals("-") ? null : text;
                bookingService.completeBooking(bookingId, note);
                send(telegramId, "Визит отмечен как завершённый.");
                session.reset();
            }
            default -> handleUnrecognizedText(telegramId);
        }
    }

    // Вспомогательные методы

    private InlineKeyboardMarkup
    completeNoShowKeyboard(Long bookingId, boolean confirmed) {

        if (!confirmed) {
            // PENDING — показываем подтвердить/отклонить (дублирует уведомление, но удобно в /today)
            return InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            btn("✅ Подтвердить", CallbackData.build(CallbackData.CONFIRM_BOOKING, bookingId)),
                            btn("❌ Отклонить", CallbackData.build(CallbackData.REJECT_BOOKING, bookingId))
                    ))
                    .build();
        }

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("☑️ Завершить", CallbackData.build(CallbackData.COMPLETE_BOOKING, bookingId)),
                        btn("🚫 No-show", CallbackData.build(CallbackData.NO_SHOW_BOOKING, bookingId))
                ))
                .build();
    }

    private InlineKeyboardButton
    btn(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private void send(Long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(Long chatId, String text,
                      InlineKeyboardMarkup keyboard) {
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
