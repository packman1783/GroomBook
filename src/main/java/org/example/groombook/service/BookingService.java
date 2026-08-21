package org.example.groombook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.exception.BookingLimitExceededException;
import org.example.groombook.exception.BookingNotFoundException;
import org.example.groombook.exception.CancellationTooLateException;
import org.example.groombook.exception.ClientBlockedException;
import org.example.groombook.exception.ClientNotFoundException;
import org.example.groombook.exception.GroomBookException;
import org.example.groombook.exception.InvalidBookingStatusException;
import org.example.groombook.exception.PetNotFoundException;
import org.example.groombook.exception.PetRefusedException;
import org.example.groombook.exception.SlotAlreadyBookedException;
import org.example.groombook.exception.SlotBlockedException;
import org.example.groombook.exception.SlotNotFoundException;
import org.example.groombook.exception.SlotTooSoonException;
import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.model.enums.SlotStatus;
import org.example.groombook.repository.BookingRepository;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.PetRepository;
import org.example.groombook.repository.TimeSlotRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Основной сервис управления записями (бронированиями) в системе GroomBook.
 * <p>
 * Отвечает за:
 * <ul>
 *   <li>Полный жизненный цикл стандартных записей клиентов (создание, подтверждение, отклонение, отмена, завершение).</li>
 *   <li>Создание ручных (договорных) записей мастером в обход базовых ограничений и шаблонов.</li>
 *   <li>Проверку ключевых бизнес-правил: лимиты записей в неделю, временные интервалы отмены/записи, статусы клиентов и питомцев.</li>
 *   <li>Интеграцию с внешними сервисами: отправку уведомлений через {@link NotificationService} и синхронизацию с Google Calendar через {@link GoogleCalendarService}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /**
     * Максимальное количество активных бронирований, разрешенное одному клиенту в неделю.
     */
    private static final int BOOKING_LIMIT_PER_WEEK = 2;

    /**
     * Минимальное количество часов до начала слота, за которое клиент еще может оформить запись.
     */
    private static final int MIN_HOURS_BEFORE_BOOKING = 1;

    /**
     * Минимальный запас времени (в часах) до начала визита, допускающий отмену брони самим клиентом.
     */
    private static final int MIN_HOURS_BEFORE_CANCEL = 24;

    private final BookingRepository bookingRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final ClientRepository clientRepository;
    private final PetRepository petRepository;
    private final NotificationService notificationService;
    private final GoogleCalendarService googleCalendarService;

    // Создание брони клиентом

    /**
     * Создает стандартную заявку на бронирование от имени клиента через Telegram-бота.
     * <p>
     * Выполняет перед сохранением ряд проверок бизнес-правил:
     * <ul>
     *   <li>Клиент не заблокирован.</li>
     *   <li>Питомец активен и ему не отказано в обслуживании.</li>
     *   <li>Слот свободен и до его начала остается больше {@link #MIN_HOURS_BEFORE_BOOKING} часов.</li>
     *   <li>Клиент не превысил недельный лимит записей {@link #BOOKING_LIMIT_PER_WEEK}.</li>
     * </ul>
     * После успешного создания переводит слот в состояние занятости и отправляет уведомление мастеру.
     */
    @Transactional
    public Booking createBooking(Long telegramId, Long slotId,
                                 Long petId, String comment) {

        Client client = findClientByTelegramId(telegramId);
        Pet pet = findPetForClient(petId, client.getId());
        TimeSlot slot = findSlot(slotId);

        // Проверяем все бизнес-правила перед созданием
        validateClientCanBook(client);
        validatePetCanBeBooked(pet);
        validateSlotIsAvailable(slot);
        validateSlotIsNotTooSoon(slot);
        validateWeeklyLimit(client.getId());

        // Создаём бронь
        Booking booking = Booking.builder()
                .slot(slot)
                .client(client)
                .pet(pet)
                .bookingType(BookingType.STANDARD)
                .status(BookingStatus.PENDING)
                .clientComment(comment)
                .build();

        slot.markBooked();
        timeSlotRepository.save(slot);
        bookingRepository.save(booking);

        log.info("Создана бронь #{} клиент={} слот={}", booking.getId(),
                client.getId(), slot.getId());

        // Уведомляем мастера о новой заявке
        notificationService.notifyMasterNewBooking(booking);

        return booking;
    }

    /**
     * Создает ручную (договорную) запись мастером.
     * <p>
     * Отличается от стандартного бронирования следующим:
     * <ul>
     *   <li>Может создаваться вне рамок основного шаблона и в нерабочие часы/дни.</li>
     *   <li>Автоматически генерирует специальный ручной слот {@link SlotStatus#MANUAL_BOOKING}.</li>
     *   <li>Сразу получает статус {@link BookingStatus#CONFIRMED}.</li>
     *   <li>Синхронизируется с Google Calendar (ошибки API календарного сервиса логируются без отката транзакции).</li>
     *   <li>Не отправляет уведомление клиенту, так как предполагается персональная договоренность.</li>
     * </ul>
     */
    @Transactional
    public Booking createManualBooking(Long clientId, Long petId,
                                       LocalDate date, LocalTime startTime,
                                       int durationHours, String comment) {

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        Pet pet = findPetForClient(petId, clientId);

        // Для договорной записи создаём слот вне шаблона
        LocalTime endTime = startTime.plusHours(durationHours);

        TimeSlot slot = TimeSlot.builder()
                .date(date)
                .startTime(startTime)
                .endTime(endTime)
                .status(SlotStatus.MANUAL_BOOKING)
                .manual(true)
                .build();

        timeSlotRepository.save(slot);

        Booking booking = Booking.builder()
                .slot(slot)
                .client(client)
                .pet(pet)
                .bookingType(BookingType.MANUAL)
                .status(BookingStatus.CONFIRMED)
                .clientComment(comment)
                .confirmedAt(LocalDateTime.now())
                .build();

        bookingRepository.save(booking);

        // Синхронизируем с Google Calendar
        try {
            String eventId = googleCalendarService.createEvent(booking);
            booking.setGcalEventId(eventId);
            bookingRepository.save(booking);
        } catch (Exception e) {
            // Ошибка Calendar не должна откатывать бронь — логируем и продолжаем
            log.error("КРИТИЧЕСКАЯ ОШИБКА Google Calendar при создании договорной записи #{}: {}",
                    booking.getId(), e.getMessage(), e);
        }

        log.info("Создана договорная запись #{} клиент={} дата={} время={}",
                booking.getId(), clientId, date, startTime);

        return booking;
    }

    // Управление статусом брони — действия мастера

    /**
     * Подтверждает заявку на бронирование мастером.
     * <p>
     * Переводит бронь из состояния {@link BookingStatus#PENDING} в {@link BookingStatus#CONFIRMED}.
     * Экспортирует событие в Google Calendar и отправляет клиенту уведомление с подтверждением.
     */
    @Transactional
    public Booking confirmBooking(Long bookingId) {
        Booking booking = findActiveBooking(bookingId);

        if (!booking.isPending()) {
            throw new InvalidBookingStatusException(bookingId, "подтвердить",
                    booking.getStatus().name());
        }

        // Создаём событие в Google Calendar
        try {
            String eventId = googleCalendarService.createEvent(booking);
            booking.confirm(eventId);
        } catch (Exception e) {
            log.error("КРИТИЧЕСКАЯ ОШИБКА Google Calendar при подтверждении брони #{}: {}",
                    bookingId, e.getMessage(), e);
            // Подтверждаем без Calendar — мастер может добавить вручную
            booking.confirm(null);
        }

        bookingRepository.save(booking);

        // Уведомляем клиента
        notificationService.notifyClientConfirmed(booking);

        log.info("Бронь #{} подтверждена мастером", bookingId);
        return booking;
    }

    /**
     * Отклоняет находящуюся на рассмотрении заявку клиента (PENDING).
     * <p>
     * Переводит бронь в статус {@link BookingStatus#CANCELLED_BY_MASTER}, освобождает временной слот
     * и уведомляет клиента с указанием причины отказа.
     */
    @Transactional
    public void rejectBooking(Long bookingId, String reason) {
        Booking booking = findActiveBooking(bookingId);

        if (!booking.isPending()) {
            throw new InvalidBookingStatusException(bookingId, "отклонить",
                    booking.getStatus().name());
        }

        booking.cancelByMaster();
        booking.getSlot().markFree();

        bookingRepository.save(booking);
        timeSlotRepository.save(booking.getSlot());

        notificationService.notifyClientCancelled(booking, reason);

        log.info("Бронь #{} отклонена мастером. Причина: {}", bookingId, reason);
    }

    /**
     * Отменяет мастером ранее уже подтвержденную бронь.
     * <p>
     * Освобождает забронированный слот, удаляет соответствующую запись из Google Calendar
     * и отправляет клиенту уведомление об отмене.
     */
    @Transactional
    public void cancelByMaster(Long bookingId, String reason) {
        Booking booking = findActiveBooking(bookingId);

        booking.cancelByMaster();
        booking.getSlot().markFree();

        deleteCalendarEvent(booking);

        bookingRepository.save(booking);
        timeSlotRepository.save(booking.getSlot());

        notificationService.notifyClientCancelled(booking, reason);

        log.info("Бронь #{} отменена мастером. Причина: {}", bookingId, reason);
    }

    /**
     * Отмечает визит как успешно завершенный.
     * <p>
     * Переводит бронь из {@link BookingStatus#CONFIRMED} в статус {@link BookingStatus#COMPLETED}
     * и сохраняет заметку мастера о проведенной процедуре.
     */
    @Transactional
    public Booking completeBooking(Long bookingId, String masterNote) {
        Booking booking = findBookingById(bookingId);

        if (!booking.isConfirmed()) {
            throw new InvalidBookingStatusException(bookingId, "завершить",
                    booking.getStatus().name());
        }

        booking.complete(masterNote);
        bookingRepository.save(booking);

        log.info("Бронь #{} завершена", bookingId);
        return booking;
    }

    /**
     * Фиксирует неявку клиента на прием без предварительного предупреждения.
     * <p>
     * Переводит статус в {@link BookingStatus#NO_SHOW}, увеличивает персональный счетчик
     * неявок у клиента на 1, освобождает временной слот и удаляет событие из Google Calendar.
     */
    @Transactional
    public Booking markNoShow(Long bookingId) {
        Booking booking = findBookingById(bookingId);

        if (!booking.isConfirmed()) {
            throw new InvalidBookingStatusException(bookingId, "отметить no-show",
                    booking.getStatus().name());
        }

        booking.markNoShow();
        booking.getSlot().markFree();

        Client client = booking.getClient();
        client.incrementNoShowCount();

        deleteCalendarEvent(booking);

        bookingRepository.save(booking);
        timeSlotRepository.save(booking.getSlot());
        clientRepository.save(client);

        log.info("No-show для брони #{}, клиент #{}, счётчик={}",
                bookingId, client.getId(), client.getNoShowCount());

        return booking;
    }

    // Отмена клиентом

    /**
     * Отменяет бронирование по инициативе клиента.
     * <p>
     * Отмена разрешается только при соблюдении следующих условий:
     * <ul>
     *   <li>Бронь принадлежит клиенту, совершающему запрос.</li>
     *   <li>До начала приема остается больше регламентированного времени (обычно не менее {@link #MIN_HOURS_BEFORE_CANCEL} часов).</li>
     * </ul>
     * При успешной отмене слот возвращается в статус свободного, событие удаляется из Google Calendar,
     * а мастер получает notification-уведомление.
     */
    @Transactional
    public void cancelByClient(Long bookingId, Long telegramId) {
        Booking booking = findBookingById(bookingId);

        // Проверяем что бронь принадлежит этому клиенту
        if (!booking.getClient().getTelegramId().equals(telegramId)) {
            throw new GroomBookException("Бронь #" + bookingId + " не принадлежит клиенту");
        }

        if (!booking.isCancellableByClient()) {
            throw new CancellationTooLateException(bookingId);
        }

        booking.cancelByClient();
        booking.getSlot().markFree();

        deleteCalendarEvent(booking);

        bookingRepository.save(booking);
        timeSlotRepository.save(booking.getSlot());

        notificationService.notifyMasterClientCancelled(booking);

        log.info("Бронь #{} отменена клиентом #{}", bookingId,
                booking.getClient().getId());
    }

    // Запросы

    /**
     * Возвращает список всех активных (ожидающих или подтвержденных) бронирований конкретного клиента.
     * Используется для формирования ответа на команду {@code /mybookings}.
     */
    @Transactional(readOnly = true)
    public List<Booking> getActiveBookingsForClient(Long telegramId) {
        Client client = findClientByTelegramId(telegramId);
        return bookingRepository.findActiveByClientId(client.getId());
    }

    /**
     * Возвращает все бронирования на указанный день.
     * Используется мастером при просмотре расписания на текущий/следующий день (команды {@code /today}, {@code /tomorrow}).
     */
    @Transactional(readOnly = true)
    public List<Booking> getBookingsForDate(LocalDate date) {
        return bookingRepository.findByDate(date);
    }

    /**
     * Находит все активные бронирования в заданной временной рамочной диапазоне.
     * Служит для проверки накладывающихся записей (например, при блокировке мастером периода отпуска).
     */
    @Transactional(readOnly = true)
    public List<Booking> getActiveBookingsInRange(LocalDate from, LocalDate to) {
        return bookingRepository.findActiveInDateRange(from, to);
    }

    /**
     * Находит все подтвержденные брони на выбранную дату.
     * Используется фоновым сервисом/планировщиком рассылки для отправки напоминаний накануне приёма.
     */
    @Transactional(readOnly = true)
    public List<Booking> getConfirmedBookingsForDate(LocalDate date) {
        return bookingRepository.findConfirmedForDate(date);
    }

    // Приватные методы — валидация и вспомогательная логика

    /**
     * Проверяет возможность клиента осуществлять бронирование.
     */
    private void validateClientCanBook(Client client) {
        if (client.isBlocked()) {
            throw new ClientBlockedException(client.getId());
        }
    }

    /**
     * Проверяет статус питомца перед бронированием.
     */
    private void validatePetCanBeBooked(Pet pet) {
        if (pet.isRefused()) {
            throw new PetRefusedException(pet.getId());
        }
        if (!pet.isActive()) {
            throw new PetNotFoundException(pet.getId());
        }
    }

    /**
     * Проверяет доступность временного слота.
     */
    private void validateSlotIsAvailable(TimeSlot slot) {
        if (slot.isBooked()) {
            throw new SlotAlreadyBookedException(slot.getId());
        }
        if (!slot.isFree()) {
            throw new SlotBlockedException(slot.getId());
        }
    }

    /**
     * Проверяет, что запрашиваемый слот наступает не слишком скоро.
     */
    private void validateSlotIsNotTooSoon(TimeSlot slot) {
        if (!slot.isCancellableByClient(MIN_HOURS_BEFORE_BOOKING)) {
            throw new SlotTooSoonException();
        }
    }

    /**
     * Проверяет персональный лимит активных броней клиента на текущую календарную неделю.
     */
    private void validateWeeklyLimit(Long clientId) {
        LocalDateTime weekStart = LocalDateTime.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusDays(7);

        long count = bookingRepository.countActiveByClientInWeek(
                clientId, weekStart, weekEnd);

        if (count >= BOOKING_LIMIT_PER_WEEK) {
            LocalDate nextMonday = weekStart.plusDays(7).toLocalDate();
            throw new BookingLimitExceededException(clientId, nextMonday);
        }
    }

    /**
     * Вспомогательный метод для безопасного удаления связанного события из Google Calendar.
     */
    private void deleteCalendarEvent(Booking booking) {
        if (booking.getGcalEventId() != null) {
            try {
                googleCalendarService.deleteEvent(booking.getGcalEventId());
            } catch (Exception e) {
                log.warn("Не удалось удалить событие Calendar для брони #{}: {}",
                        booking.getId(), e.getMessage());
            }
        }
    }

    private Client findClientByTelegramId(Long telegramId) {
        return clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));
    }

    private Pet findPetForClient(Long petId, Long clientId) {
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new PetNotFoundException(petId));
        if (!pet.getClient().getId().equals(clientId)) {
            throw new PetNotFoundException(petId);
        }
        return pet;
    }

    private TimeSlot findSlot(Long slotId) {
        return timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));
    }

    public Booking findBookingById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    private Booking findActiveBooking(Long bookingId) {
        Booking booking = findBookingById(bookingId);
        if (!booking.isActive()) {
            throw new InvalidBookingStatusException(bookingId, "действие",
                    booking.getStatus().name());
        }
        return booking;
    }
}
