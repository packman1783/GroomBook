package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.keyboard.InlineKeyboardFactory;
import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.exception.*;
import org.example.groombook.model.Booking;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.PetType;
import org.example.groombook.service.BookingService;
import org.example.groombook.service.ClientService;
import org.example.groombook.service.ScheduleService;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClientHandler {

    private final ClientService         clientService;
    private final BookingService        bookingService;
    private final ScheduleService       scheduleService;
    private final SessionManager        sessionManager;
    private final InlineKeyboardFactory keyboards;
    private final TelegramClient        telegramClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Команды

    public void handleCommand(Long telegramId, String text) {
        String command = text.split("\\s+")[0].toLowerCase();

        switch (command) {
            case "/start"       -> handleStart(telegramId);
            case "/book"        -> handleBookStart(telegramId);
            case "/mybookings"  -> handleMyBookings(telegramId);
            case "/addpet"      -> handleAddPetStart(telegramId);
            default -> send(telegramId, "Не знаю такую команду. Доступно: /book, /mybookings, /addpet");
        }
    }

    public void handleUnrecognizedText(Long telegramId) {
        send(telegramId, "Не понимаю это сообщение. Используйте /book чтобы записаться " +
                "или /mybookings чтобы посмотреть свои записи.");
    }

    // /start — регистрация нового клиента

    private void handleStart(Long telegramId) {
        if (clientService.isRegistered(telegramId)) {
            send(telegramId, "Привет! Чтобы записаться, используйте /book.\n" +
                    "Посмотреть свои записи — /mybookings.");
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        session.setState(SessionState.AWAITING_NAME);
        send(telegramId, "Добро пожаловать! 🐾 Для начала, как вас зовут?");
    }

    // /addpet — добавление питомца

    private void handleAddPetStart(Long telegramId) {
        if (!clientService.isRegistered(telegramId)) {
            send(telegramId, "Сначала нужно зарегистрироваться — отправьте /start");
            return;
        }
        sessionManager.get(telegramId).setState(SessionState.AWAITING_PET_NAME);
        send(telegramId, "Как зовут питомца?");
    }

    // /book — бронирование

    private void handleBookStart(Long telegramId) {
        if (!clientService.isRegistered(telegramId)) {
            send(telegramId, "Сначала нужно зарегистрироваться — отправьте /start");
            return;
        }

        List<LocalDate> dates = scheduleService.getAvailableDates();
        if (dates.isEmpty()) {
            send(telegramId, "К сожалению, сейчас нет свободных слотов. Загляните позже 🙏");
            return;
        }

        send(telegramId, "Выберите дату:", keyboards.datesKeyboard(dates));
    }

    private void handleDateSelected(Long telegramId, LocalDate date, String callbackId) {
        List<TimeSlot> slots = scheduleService.getAvailableSlots(date);
        answerCallback(callbackId, null);

        if (slots.isEmpty()) {
            send(telegramId, "На эту дату свободных слотов уже нет. Выберите другую.");
            return;
        }

        send(telegramId, "Выберите время на " + date.format(DATE_FMT) + ":",
                keyboards.slotsKeyboard(slots));
    }

    private void handleSlotSelected(Long telegramId, Long slotId, String callbackId) {
        answerCallback(callbackId, null);

        List<Pet> pets = clientService.getActivePets(telegramId);
        if (pets.isEmpty()) {
            send(telegramId, "У вас пока нет добавленных питомцев. " +
                    "Сначала добавьте питомца командой /addpet, затем повторите /book.");
            return;
        }

        sessionManager.get(telegramId).setPendingSlotId(slotId);
        send(telegramId, "Для какого питомца запись?", keyboards.petsKeyboard(pets));
    }

    private void handlePetSelectedForBooking(Long telegramId, Long petId, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        session.setPendingPetId(petId);
        session.setState(SessionState.AWAITING_BOOKING_COMMENT);

        send(telegramId, "Оставьте комментарий к записи (например, особенности шерсти) " +
                "или отправьте \"-\" чтобы пропустить.");
    }

    private void finishBooking(Long telegramId, String commentText) {
        UserSession session = sessionManager.get(telegramId);
        String comment = commentText.equals("-") ? null : commentText;

        try {
            bookingService.createBooking(telegramId, session.getPendingSlotId(),
                    session.getPendingPetId(), comment);

            send(telegramId, "✅ Заявка отправлена! Мастер подтвердит запись в ближайшее время.");
        } catch (BookingLimitExceededException e) {
            send(telegramId, "На этой неделе вы уже записаны максимальное количество раз (2). " +
                    "Попробуйте выбрать слот на следующей неделе.");
        } catch (SlotTooSoonException e) {
            send(telegramId, "Запись возможна не позднее чем за 1 час до начала. " +
                    "Выберите другое время через /book.");
        } catch (SlotAlreadyBookedException e) {
            send(telegramId, "К сожалению, этот слот только что забронировали. " +
                    "Выберите другое время через /book.");
        } catch (ClientBlockedException e) {
            send(telegramId, "Запись временно недоступна. Свяжитесь с мастером напрямую.");
        } catch (PetRefusedException e) {
            send(telegramId, "К сожалению, запись для этого питомца недоступна.");
        } catch (GroomBookException e) {
            log.warn("Ошибка при создании брони: {}", e.getMessage());
            send(telegramId, "Не удалось создать запись. Попробуйте ещё раз через /book.");
        } finally {
            session.reset();
        }
    }

    // /mybookings — список и отмена

    private void handleMyBookings(Long telegramId) {
        List<Booking> bookings = bookingService.getActiveBookingsForClient(telegramId);

        if (bookings.isEmpty()) {
            send(telegramId, "У вас нет активных записей. Записаться можно через /book.");
            return;
        }

        for (Booking booking : bookings) {
            TimeSlot slot = booking.getSlot();
            String statusLabel = booking.isConfirmed() ? "✅ подтверждена" : "⏳ ожидает подтверждения";

            String text = String.format("📅 %s в %s–%s\n🐾 %s\nСтатус: %s",
                    slot.getDate().format(DATE_FMT),
                    slot.getStartTime().format(TIME_FMT),
                    slot.getEndTime().format(TIME_FMT),
                    booking.getPet().getName(),
                    statusLabel);

            send(telegramId, text, keyboards.cancelBookingKeyboard(booking.getId()));
        }
    }

    private void handleCancelRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        send(telegramId, "Подтвердите отмену записи:", keyboards.confirmCancelKeyboard(bookingId));
    }

    private void handleCancelConfirmed(Long telegramId, Long bookingId, String callbackId) {
        try {
            bookingService.cancelByClient(bookingId, telegramId);
            answerCallback(callbackId, "Запись отменена");
            send(telegramId, "Запись отменена. Ждём вас в другой раз! 🐾");
        } catch (CancellationTooLateException e) {
            answerCallback(callbackId, null);
            send(telegramId, "Отменить запись можно не позднее чем за 24 часа до начала. " +
                    "Свяжитесь с мастером напрямую если это срочно.");
        } catch (GroomBookException e) {
            answerCallback(callbackId, null);
            send(telegramId, "Не удалось отменить запись. Попробуйте ещё раз.");
        }
    }

    // Многошаговые текстовые сценарии

    public void handleTextInput(Long telegramId, String text, SessionState state) {
        UserSession session = sessionManager.get(telegramId);

        switch (state) {
            case AWAITING_NAME -> {
                session.setPendingName(text);
                session.setState(SessionState.AWAITING_PHONE);
                send(telegramId, "Отлично, " + text + "! Теперь укажите номер телефона " +
                        "(в формате +79991234567):");
            }
            case AWAITING_PHONE -> handlePhoneEntered(telegramId, text, session);
            case AWAITING_PET_NAME -> {
                session.setPendingPetName(text);
                send(telegramId, "Кто это?", keyboards.petTypeKeyboard());
            }
            case AWAITING_BOOKING_COMMENT -> finishBooking(telegramId, text);
            default -> handleUnrecognizedText(telegramId);
        }
    }

    private void handlePhoneEntered(Long telegramId, String phone, UserSession session) {
        try {
            clientService.getOrCreateClient(telegramId, session.getPendingName(), phone);
            session.reset();
            send(telegramId, "✅ Регистрация завершена!\n\n" +
                    "Теперь добавьте питомца командой /addpet, " +
                    "а затем записывайтесь на стрижку через /book.");
        } catch (PhoneAlreadyRegisteredException e) {
            send(telegramId, "Этот номер телефона уже зарегистрирован. " +
                    "Если это ваш номер — свяжитесь с мастером напрямую.");
            session.reset();
        }
    }

    // Callback-кнопки

    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.BOOK_DATE -> handleDateSelected(telegramId,
                    LocalDate.parse(CallbackData.payload(data)), callbackId);

            case CallbackData.BOOK_SLOT -> handleSlotSelected(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.BOOK_PET -> handlePetSelectedForBooking(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.PET_TYPE -> handlePetTypeSelected(telegramId,
                    CallbackData.payload(data), callbackId);

            case CallbackData.CANCEL_BOOKING -> handleCancelRequest(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.CANCEL_CONFIRM -> handleCancelConfirmed(telegramId,
                    CallbackData.payloadAsLong(data), callbackId);

            case CallbackData.CANCEL_ABORT -> {
                answerCallback(callbackId, "Отменено");
                send(telegramId, "Хорошо, запись остаётся в силе.");
            }

            default -> answerCallback(callbackId, null);
        }
    }

    private void handlePetTypeSelected(Long telegramId, String typeStr, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        PetType type = PetType.valueOf(typeStr);

        Pet pet = clientService.addPet(telegramId, session.getPendingPetName(), type, null);
        session.reset();

        send(telegramId, "🎉 Питомец \"" + pet.getName() + "\" добавлен! " +
                "Теперь можно записаться через /book.");
    }

    // Вспомогательные методы отправки

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
            log.error("Ошибка отправки сообщения клиенту chatId={}: {}", chatId, e.getMessage());
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
