package org.example.groombook.infrastructure.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.service.NotificationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.format.DateTimeFormatter;

/**
 * Реализация уведомлений через Telegram.
 *
 * ВАЖНО: начиная с telegrambots 10.x бот больше не наследует AbsSender —
 * вместо этого TelegramClient внедряется как обычный Spring-бин и используется
 * для вызова execute(). Это даёт нам возможность использовать TelegramClient
 * прямо здесь, без привязки к конкретному классу бота.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService implements NotificationService {

    /** Бин TelegramClient — создаётся в TelegramBotConfig (см. bot-слой) */
    private final TelegramClient telegramClient;

    @Value("${grooming.master.telegram-id}")
    private Long masterTelegramId;

    @Value("${grooming.master.address:}")
    private String masterAddress;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // ── Уведомления мастеру ───────────────────────────────────────────────────

    @Override
    public void notifyMasterNewBooking(Booking booking) {
        Client   client = booking.getClient();
        Pet      pet    = booking.getPet();
        TimeSlot slot   = booking.getSlot();

        String text = String.format("""
                🐾 *Новая заявка на запись*
                
                👤 %s
                📞 %s
                🐕 %s (%s)
                📅 %s в %s–%s
                %s
                """,
                escapeMarkdown(client.getName()),
                client.getPhone(),
                escapeMarkdown(pet.getName()),
                pet.getType().name().toLowerCase(),
                slot.getDate().format(DATE_FMT),
                slot.getStartTime().format(TIME_FMT),
                slot.getEndTime().format(TIME_FMT),
                booking.getClientComment() != null
                        ? "💬 " + escapeMarkdown(booking.getClientComment())
                        : "_Комментарий не оставлен_"
        );

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        callbackButton("✅ Подтвердить",
                                "CONFIRM_BOOKING:" + booking.getId()),
                        callbackButton("❌ Отклонить",
                                "REJECT_BOOKING:" + booking.getId())
                ))
                .build();

        sendToMaster(text, keyboard);
    }

    @Override
    public void notifyMasterClientCancelled(Booking booking) {
        TimeSlot slot = booking.getSlot();
        String text = String.format("""
                ℹ️ *Клиент отменил запись*
                
                👤 %s
                📅 %s в %s
                Слот освобождён ✅
                """,
                escapeMarkdown(booking.getClient().getName()),
                slot.getDate().format(DATE_FMT),
                slot.getStartTime().format(TIME_FMT)
        );

        sendToMaster(text, null);
    }

    // ── Уведомления клиенту ───────────────────────────────────────────────────

    @Override
    public void notifyClientConfirmed(Booking booking) {
        TimeSlot slot = booking.getSlot();
        String text = String.format("""
                ✅ *Запись подтверждена\\!*
                
                📅 %s в %s–%s
                🐾 %s
                %s
                
                Ждём вас\\! 🎉
                """,
                slot.getDate().format(DATE_FMT),
                slot.getStartTime().format(TIME_FMT),
                slot.getEndTime().format(TIME_FMT),
                escapeMarkdown(booking.getPet().getName()),
                masterAddress.isBlank()
                        ? ""
                        : "📍 " + escapeMarkdown(masterAddress)
        );

        sendToClient(booking.getClient().getTelegramId(), text, null);
    }

    @Override
    public void notifyClientCancelled(Booking booking, String reason) {
        TimeSlot slot = booking.getSlot();
        String text = String.format("""
                😔 *Запись отменена*
                
                📅 %s в %s
                
                К сожалению, мастер не может принять вас в это время\\.
                Вы можете выбрать другой удобный слот\\.
                """,
                slot.getDate().format(DATE_FMT),
                slot.getStartTime().format(TIME_FMT)
        );
        // Причина клиенту намеренно не раскрывается

        sendToClient(booking.getClient().getTelegramId(), text, null);
    }

    @Override
    public void sendReminderToClient(Booking booking) {
        TimeSlot slot = booking.getSlot();
        String text = String.format("""
                🔔 *Напоминание о записи*
                
                Завтра в *%s–%s* стрижка %s 🐾
                %s
                
                Ждём вас\\!
                """,
                slot.getStartTime().format(TIME_FMT),
                slot.getEndTime().format(TIME_FMT),
                escapeMarkdown(booking.getPet().getName()),
                masterAddress.isBlank()
                        ? ""
                        : "📍 " + escapeMarkdown(masterAddress)
        );

        sendToClient(booking.getClient().getTelegramId(), text, null);
    }

    @Override
    public void sendMessageToClient(Long telegramId, String message) {
        sendToClient(telegramId, escapeMarkdown(message), null);
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private void sendToMaster(String text, InlineKeyboardMarkup keyboard) {
        send(masterTelegramId, text, keyboard);
    }

    private void sendToClient(Long telegramId, String text,
                              InlineKeyboardMarkup keyboard) {
        send(telegramId, text, keyboard);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("MarkdownV2")
                .replyMarkup(keyboard)
                .build();
        try {
            // Главное отличие от старого API:
            // вызываем execute() на внедрённом TelegramClient, а не на самом боте
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения в Telegram chatId={}: {}",
                    chatId, e.getMessage());
        }
    }

    private InlineKeyboardButton callbackButton(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    /**
     * Экранирование спецсимволов для MarkdownV2.
     * Telegram требует экранировать: _ * [ ] ( ) ~ ` > # + - = | { } . !
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!])", "\\\\$1");
    }
}