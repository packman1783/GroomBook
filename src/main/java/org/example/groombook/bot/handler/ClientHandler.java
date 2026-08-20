package org.example.groombook.bot.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.keyboard.InlineKeyboardFactory;
import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;
import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.exception.BookingLimitExceededException;
import org.example.groombook.exception.CancellationTooLateException;
import org.example.groombook.exception.ClientBlockedException;
import org.example.groombook.exception.GroomBookException;
import org.example.groombook.exception.PetRefusedException;
import org.example.groombook.exception.PhoneAlreadyRegisteredException;
import org.example.groombook.exception.SlotAlreadyBookedException;
import org.example.groombook.exception.SlotTooSoonException;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Хэндлер взаимодействия с клиентами груминг-салона.
 * <p>
 * Отвечает за:
 * <ul>
 *   <li>Первичную регистрацию (имя, номер телефона)</li>
 *   <li>Добавление питомцев пользователя</li>
 *   <li>Пошаговый мастер записи на услугу (выбор даты -> слота -> питомца -> комментарий)</li>
 *   <li>Просмотр и отмену собственных активных броней</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientHandler {

    private final ClientService clientService;
    private final BookingService bookingService;
    private final ScheduleService scheduleService;
    private final SessionManager sessionManager;
    private final InlineKeyboardFactory keyboards;
    private final TelegramClient telegramClient;

    /** Формат даты для отображения клиенту (например, "15 мая"). */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM");

    /** Формат времени для отображения слотов (например, "14:30"). */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // --- Обработка команд ---

    /**
     * Точка входа для обработки текстовых команд клиента (начинающихся с "/").
     */
    public void handleCommand(Long telegramId, String text) {
        String command = text.split("\\s+")[0].toLowerCase();

        switch (command) {
            case "/start" -> handleStart(telegramId);
            case "/book" -> handleBookStart(telegramId);
            case "/mybookings" -> handleMyBookings(telegramId);
            case "/addpet" -> handleAddPetStart(telegramId);
            case "/help" -> handleHelp(telegramId);
            default -> send(telegramId, "Не знаю такую команду. Попробуйте /help для списка команд.");
        }
    }

    /**
     * Выводит справку по командам бота для клиента.
     */
    private void handleHelp(Long telegramId) {
        String helpText = """
                🐾 *Справка по GroomBook* 🐾
                
                Я помогу вам записаться к грумеру быстро и удобно!
                
                *Доступные команды:*
                /book — Выбрать дату и время для записи
                /mybookings — Посмотреть ваши активные записи или отменить их
                /addpet — Добавить нового питомца в профиль
                /help — Показать это сообщение
                /start — Начало работы / Регистрация
                
                *Как это работает:*
                1. Зарегистрируйтесь при первом входе.
                2. Добавьте одного или нескольких питомцев через /addpet.
                3. Выберите удобное время через /book.
                4. Дождитесь подтверждения от мастера (вам придёт уведомление).
                
                Если у вас возникли вопросы или нужно перенести запись менее чем за 24 часа, пожалуйста, свяжитесь с мастером напрямую.
                """;
        send(telegramId, helpText);
    }

    /**
     * Обрабатывает нераспознанный текстовый ввод вне контекста активного шага мастера (FSM).
     */
    public void handleUnrecognizedText(Long telegramId) {
        send(telegramId, "Не понимаю это сообщение. Используйте /book чтобы записаться " +
                "или /mybookings чтобы посмотреть свои записи.");
    }

    // --- Сценарии команд ---

    /**
     * Старт работы с ботом: проверка регистрации и запуск мастера знакомства.
     */
    private void handleStart(Long telegramId) {
        if (clientService.isRegistered(telegramId)) {
            send(telegramId, """
                    Привет! 👋 Рады видеть вас снова.
                    
                    Чтобы записаться на груминг, используйте команду /book.
                    Посмотреть свои записи — /mybookings.
                    Если нужна помощь — /help.
                    """);
            return;
        }

        UserSession session = sessionManager.get(telegramId);
        session.setState(SessionState.AWAITING_NAME);
        send(telegramId, """
                Добро пожаловать в GroomBook! 🐾
                
                Я помогу вам записать вашего питомца к грумеру. 
                Для начала давайте познакомимся.
                
                Как вас зовут? (Введите имя)
                """);
    }

    /**
     * Запуск процесса добавления нового питомца.
     */
    private void handleAddPetStart(Long telegramId) {
        if (!clientService.isRegistered(telegramId)) {
            send(telegramId, "Сначала нужно зарегистрироваться — отправьте /start");
            return;
        }
        sessionManager.get(telegramId).setState(SessionState.AWAITING_PET_NAME);
        send(telegramId, "Как зовут питомца?");
    }

    /**
     * Начало диалога онлайн-записи: отображение клавиатуры с доступными датами.
     */
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

    /**
     * Обработка выбора даты клиентом: вывод свободных временных слотов.
     */
    private void handleDateSelected(Long telegramId, LocalDate date, String callbackId) {
        List<TimeSlot> slots = scheduleService.getAvailableSlots(date);
        answerCallback(callbackId, null);

        if (slots.isEmpty()) {
            send(telegramId, "На эту дату свободных слотов уже нет. Выберите другую.");
            return;
        }

        send(telegramId, "Выберите время на " + date.format(DATE_FMT) + ":", keyboards.slotsKeyboard(slots));
    }

    /**
     * Обработка выбора слота: вывод списка питомцев клиента для привязки визита.
     */
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

    /**
     * Обработка выбора питомца: переход к этапу ввода комментария.
     */
    private void handlePetSelectedForBooking(Long telegramId, Long petId, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        session.setPendingPetId(petId);
        session.setState(SessionState.AWAITING_BOOKING_COMMENT);

        send(telegramId, "Оставьте комментарий к записи (например, особенности шерсти) " +
                "или нажмите кнопку ниже, чтобы пропустить.", keyboards.skipCommentKeyboard());
    }

    /**
     * Завершение мастера записи: вызов бизнес-логики создания записи и обработка возможных ошибок.
     */
    private void finishBooking(Long telegramId, String commentText) {
        UserSession session = sessionManager.get(telegramId);
        String comment = "-".equals(commentText) ? null : commentText;

        try {
            Booking booking = bookingService.createBooking(telegramId, session.getPendingSlotId(), session.getPendingPetId(), comment);
            
            String successMsg = String.format("""
                    ✅ Заявка отправлена! 
                    
                    *Детали записи:*
                    📅 Дата: %s
                    ⏰ Время: %s
                    🐾 Питомец: %s
                    
                    Мастер подтвердит запись в ближайшее время. Вы получите уведомление!""",
                    booking.getSlot().getDate().format(DATE_FMT),
                    booking.getSlot().getStartTime().format(TIME_FMT),
                    booking.getPet().getName());
            
            send(telegramId, successMsg);
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

    /**
     * Просмотр списка активных (подтвержденных и ожидающих) бронирований текущего клиента.
     */
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

    /**
     * Запрос на отмену записи: отправка инлайн-кнопок для подтверждения действия.
     */
    private void handleCancelRequest(Long telegramId, Long bookingId, String callbackId) {
        answerCallback(callbackId, null);
        send(telegramId, "Подтвердите отмену записи:", keyboards.confirmCancelKeyboard(bookingId));
    }

    /**
     * Окончательное подтверждение отмены записи со стороны клиента.
     */
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

    // --- Многошаговый текстовый ввод ---

    /**
     * Обработка пользовательского текстового ввода в зависимости от текущего состояния сессии.
     */
    public void handleTextInput(Long telegramId, String text, SessionState state) {
        UserSession session = sessionManager.get(telegramId);

        switch (state) {
            case AWAITING_NAME -> {
                session.setPendingName(text);
                session.setState(SessionState.AWAITING_PHONE);
                send(telegramId, "Отлично, " + text + "! Теперь укажите номер телефона " +
                        "(в формате +79991234567) или нажмите кнопку ниже, чтобы отправить свой текущий номер.", keyboards.contactKeyboard());
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

    /**
     * Обработка полученного контакта.
     */
    public void handleContact(Long telegramId, String phone, SessionState state) {
        UserSession session = sessionManager.get(telegramId);
        if (state == SessionState.AWAITING_PHONE) {
            handlePhoneEntered(telegramId, phone, session);
        } else {
            handleUnrecognizedText(telegramId);
        }
    }
    private void handlePhoneEntered(Long telegramId, String phone, UserSession session) {
        try {
            clientService.getOrCreateClient(telegramId, session.getPendingName(), phone);
            session.reset();
            send(telegramId, """
                    ✅ Регистрация завершена!
                    
                    Теперь добавьте питомца командой /addpet, \
                    а затем записывайтесь на стрижку через /book.""", new ReplyKeyboardRemove(true));
        } catch (PhoneAlreadyRegisteredException e) {
            send(telegramId, "Этот номер телефона уже зарегистрирован. " +
                    "Если это ваш номер — свяжитесь с мастером напрямую.", new ReplyKeyboardRemove(true));
            session.reset();
        }
    }

    // --- Обработка callbacks ---

    /**
     * Диспетчеризация callback-запросов (нажатий на инлайн-кнопки) от клиентов.
     */
    public void handleCallback(Long telegramId, String data, String callbackId) {
        String prefix = CallbackData.prefix(data);

        switch (prefix) {
            case CallbackData.BOOK_DATE -> handleDateSelected(telegramId, LocalDate.parse(CallbackData.payload(data)), callbackId);
            case CallbackData.BOOK_SLOT -> handleSlotSelected(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.BOOK_PET -> handlePetSelectedForBooking(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.SKIP_COMMENT -> {
                answerCallback(callbackId, null);
                finishBooking(telegramId, "-");
            }
            case CallbackData.PET_TYPE -> handlePetTypeSelected(telegramId, CallbackData.payload(data), callbackId);
            case CallbackData.CANCEL_BOOKING -> handleCancelRequest(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.CANCEL_CONFIRM -> handleCancelConfirmed(telegramId, CallbackData.payloadAsLong(data), callbackId);
            case CallbackData.CANCEL_ABORT -> {
                answerCallback(callbackId, "Отменено");
                send(telegramId, "Хорошо, запись остаётся в силе.");
            }
            case CallbackData.BOOK_CANCEL -> {
                answerCallback(callbackId, "Запись отменена");
                sessionManager.clear(telegramId);
                send(telegramId, "Запись прервана. Если передумаете — используйте /book снова.");
            }
            default -> answerCallback(callbackId, null);
        }
    }

    /**
     * Обработка выбора типа питомца (собака, кошка и т.д.) при создании карточки питомца.
     */
    private void handlePetTypeSelected(Long telegramId, String typeStr, String callbackId) {
        answerCallback(callbackId, null);

        UserSession session = sessionManager.get(telegramId);
        PetType type = PetType.valueOf(typeStr);

        Pet pet = clientService.addPet(telegramId, session.getPendingPetName(), type, null);
        session.reset();

        send(telegramId, "🎉 Питомец \"" + pet.getName() + "\" добавлен!Теперь можно записаться через /book.");
    }

    // --- Вспомогательные методы отправки ---

    /**
     * Отправляет обычное текстовое сообщение пользователю.
     */
    private void send(Long chatId, String text) {
        send(chatId, text, null);
    }

    /**
     * Отправляет текстовое сообщение пользователю с прикрепленной клавиатурой.
     */
    private void send(Long chatId, String text, ReplyKeyboard keyboard) {
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

    /**
     * Отправляет всплывающее уведомление или снимает состояние ожидания у кнопки (AnswerCallbackQuery).
     */
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
