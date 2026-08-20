package org.example.groombook.service;

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
import org.example.groombook.exception.SlotTooSoonException;

import org.example.groombook.model.Booking;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.BookingStatus;
import org.example.groombook.model.enums.BookingType;
import org.example.groombook.model.enums.ClientStatus;
import org.example.groombook.model.enums.PetDifficulty;
import org.example.groombook.model.enums.PetType;
import org.example.groombook.model.enums.SlotStatus;

import org.example.groombook.repository.BookingRepository;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.PetRepository;
import org.example.groombook.repository.TimeSlotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService")
class BookingServiceTest {

    // ── Моки всех зависимостей ────────────────────────────────────────────────
    @Mock
    BookingRepository bookingRepository;
    @Mock
    TimeSlotRepository timeSlotRepository;
    @Mock
    ClientRepository clientRepository;
    @Mock
    PetRepository petRepository;
    @Mock
    NotificationService notificationService;
    @Mock
    GoogleCalendarService googleCalendarService;

    @InjectMocks
    BookingService bookingService;

    // ── Общие тестовые данные — создаются заново перед каждым тестом ─────────
    private Client activeClient;
    private Client blockedClient;
    private Pet activePet;
    private Pet refusedPet;
    private TimeSlot freeSlot;
    private TimeSlot bookedSlot;
    private TimeSlot blockedSlot;
    private Booking pendingBooking;
    private Booking confirmedBooking;

    @BeforeEach
    void setUp() {
        // Активный клиент
        activeClient = Client.builder()
                .id(1L)
                .telegramId(100L)
                .name("Иван Петров")
                .phone("+79991234567")
                .status(ClientStatus.ACTIVE)
                .noShowCount(0)
                .build();

        // Заблокированный клиент
        blockedClient = Client.builder()
                .id(2L)
                .telegramId(200L)
                .name("Пётр Иванов")
                .phone("+79997654321")
                .status(ClientStatus.BLOCKED)
                .statusReason("Агрессивное животное")
                .noShowCount(0)
                .build();

        // Активный питомец
        activePet = Pet.builder()
                .id(10L)
                .client(activeClient)
                .name("Рекс")
                .type(PetType.DOG)
                .difficulty(PetDifficulty.EASY)
                .active(true)
                .build();

        // Питомец с отказом
        refusedPet = Pet.builder()
                .id(11L)
                .client(activeClient)
                .name("Бешеный")
                .type(PetType.DOG)
                .difficulty(PetDifficulty.REFUSED)
                .active(true)
                .build();

        // Свободный слот через 2 часа от текущего момента (не попадает под лимит 1 час)
        freeSlot = TimeSlot.builder()
                .id(100L)
                .date(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 0))
                .status(SlotStatus.FREE)
                .manual(false)
                .build();

        // Уже забронированный слот
        bookedSlot = TimeSlot.builder()
                .id(101L)
                .date(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(14, 0))
                .status(SlotStatus.BOOKED)
                .manual(false)
                .build();

        // Заблокированный мастером слот
        blockedSlot = TimeSlot.builder()
                .id(102L)
                .date(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(16, 0))
                .status(SlotStatus.BLOCKED)
                .blockReason("Стоматолог")
                .manual(false)
                .build();

        // Бронь в статусе PENDING
        pendingBooking = Booking.builder()
                .id(1000L)
                .slot(freeSlot)
                .client(activeClient)
                .pet(activePet)
                .bookingType(BookingType.STANDARD)
                .status(BookingStatus.PENDING)
                .build();

        // Бронь в статусе CONFIRMED
        confirmedBooking = Booking.builder()
                .id(1001L)
                .slot(freeSlot)
                .client(activeClient)
                .pet(activePet)
                .bookingType(BookingType.STANDARD)
                .status(BookingStatus.CONFIRMED)
                .gcalEventId("gcal-event-123")
                .build();
    }

    // createBooking — создание брони клиентом

    @Nested
    @DisplayName("createBooking")
    class CreateBooking {

        @Test
        @DisplayName("✅ успешное бронирование свободного слота")
        void createBooking_success() {
            // given
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L))
                    .thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(100L))
                    .thenReturn(Optional.of(freeSlot));
            when(bookingRepository.countActiveByClientInWeek(anyLong(), any(), any()))
                    .thenReturn(0L);
            when(bookingRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            Booking result = bookingService.createBooking(100L, 100L, 10L, "Шерсть запуталась");

            // then
            assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(result.getClientComment()).isEqualTo("Шерсть запуталась");
            assertThat(freeSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);

            verify(timeSlotRepository).save(freeSlot);
            verify(bookingRepository).save(any(Booking.class));
            verify(notificationService).notifyMasterNewBooking(any(Booking.class));
        }

        @Test
        @DisplayName("✅ бронирование без комментария (null) — допустимо")
        void createBooking_withoutComment_success() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(100L)).thenReturn(Optional.of(freeSlot));
            when(bookingRepository.countActiveByClientInWeek(anyLong(), any(), any())).thenReturn(0L);
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Booking result = bookingService.createBooking(100L, 100L, 10L, null);

            assertThat(result.getClientComment()).isNull();
            verify(bookingRepository).save(any(Booking.class));
        }

        @Test
        @DisplayName("❌ клиент заблокирован → ClientBlockedException")
        void createBooking_clientBlocked_throwsException() {
            // Создаём питомца, принадлежащего заблокированному клиенту (id = 2L)
            Pet blockedClientPet = Pet.builder()
                    .id(10L)
                    .client(blockedClient)
                    .name("Рекс")
                    .type(PetType.DOG)
                    .difficulty(PetDifficulty.EASY)
                    .active(true)
                    .build();

            when(clientRepository.findByTelegramId(200L)).thenReturn(Optional.of(blockedClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(blockedClientPet));
            when(timeSlotRepository.findById(100L)).thenReturn(Optional.of(freeSlot));

            assertThatThrownBy(() ->
                    bookingService.createBooking(200L, 100L, 10L, null))
                    .isInstanceOf(ClientBlockedException.class);

            verify(bookingRepository, never()).save(any());
            verify(notificationService, never()).notifyMasterNewBooking(any());
        }

        @Test
        @DisplayName("❌ слот уже занят → SlotAlreadyBookedException")
        void createBooking_slotBooked_throwsException() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(101L)).thenReturn(Optional.of(bookedSlot));

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 101L, 10L, null))
                    .isInstanceOf(SlotAlreadyBookedException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ слот заблокирован мастером → SlotBlockedException")
        void createBooking_slotBlocked_throwsException() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(102L)).thenReturn(Optional.of(blockedSlot));

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 102L, 10L, null))
                    .isInstanceOf(SlotBlockedException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ слот начинается менее чем через час → SlotTooSoonException")
        void createBooking_slotTooSoon_throwsException() {
            // Слот начинается через 30 минут — слишком поздно для записи
            TimeSlot soonSlot = TimeSlot.builder()
                    .id(103L)
                    .date(LocalDate.now())
                    .startTime(LocalTime.now().plusMinutes(30))
                    .endTime(LocalTime.now().plusMinutes(150))
                    .status(SlotStatus.FREE)
                    .build();

            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(103L)).thenReturn(Optional.of(soonSlot));

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 103L, 10L, null))
                    .isInstanceOf(SlotTooSoonException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ превышен лимит 2 брони в неделю → BookingLimitExceededException")
        void createBooking_weeklyLimitExceeded_throwsException() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.findById(100L)).thenReturn(Optional.of(freeSlot));
            // Уже 2 активные брони на этой неделе
            when(bookingRepository.countActiveByClientInWeek(anyLong(), any(), any()))
                    .thenReturn(2L);

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 100L, 10L, null))
                    .isInstanceOf(BookingLimitExceededException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ питомец помечен как REFUSED → PetRefusedException")
        void createBooking_petRefused_throwsException() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(11L)).thenReturn(Optional.of(refusedPet));
            when(timeSlotRepository.findById(100L)).thenReturn(Optional.of(freeSlot));

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 100L, 11L, null))
                    .isInstanceOf(PetRefusedException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ клиент не найден → ClientNotFoundException")
        void createBooking_clientNotFound_throwsException() {
            when(clientRepository.findByTelegramId(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    bookingService.createBooking(999L, 100L, 10L, null))
                    .isInstanceOf(ClientNotFoundException.class);
        }

        @Test
        @DisplayName("❌ питомец не принадлежит клиенту → PetNotFoundException")
        void createBooking_petBelongsToAnotherClient_throwsException() {
            Pet foreignPet = Pet.builder()
                    .id(99L)
                    .client(blockedClient)  // принадлежит другому клиенту
                    .name("Чужой")
                    .type(PetType.CAT)
                    .difficulty(PetDifficulty.EASY)
                    .active(true)
                    .build();

            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(99L)).thenReturn(Optional.of(foreignPet));

            assertThatThrownBy(() ->
                    bookingService.createBooking(100L, 100L, 99L, null))
                    .isInstanceOf(PetNotFoundException.class);
        }
    }

    // confirmBooking — подтверждение мастером

    @Nested
    @DisplayName("confirmBooking")
    class ConfirmBooking {

        @Test
        @DisplayName("✅ PENDING → CONFIRMED, событие создаётся в Calendar")
        void confirmBooking_success() {
            when(bookingRepository.findById(1000L)).thenReturn(Optional.of(pendingBooking));
            when(googleCalendarService.createEvent(any())).thenReturn("gcal-event-abc");
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Booking result = bookingService.confirmBooking(1000L);

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(result.getGcalEventId()).isEqualTo("gcal-event-abc");
            assertThat(result.getConfirmedAt()).isNotNull();

            verify(notificationService).notifyClientConfirmed(any());
        }

        @Test
        @DisplayName("✅ ошибка Calendar не откатывает подтверждение")
        void confirmBooking_calendarFails_stillConfirms() {
            when(bookingRepository.findById(1000L)).thenReturn(Optional.of(pendingBooking));
            when(googleCalendarService.createEvent(any()))
                    .thenThrow(new RuntimeException("Calendar API недоступен"));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Не должно выбросить исключение
            Booking result = bookingService.confirmBooking(1000L);

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(result.getGcalEventId()).isNull(); // событие не создалось, но бронь подтверждена
            verify(notificationService).notifyClientConfirmed(any());
        }

        @Test
        @DisplayName("❌ попытка подтвердить уже подтверждённую бронь → InvalidBookingStatusException")
        void confirmBooking_alreadyConfirmed_throwsException() {
            when(bookingRepository.findById(1001L)).thenReturn(Optional.of(confirmedBooking));

            assertThatThrownBy(() -> bookingService.confirmBooking(1001L))
                    .isInstanceOf(InvalidBookingStatusException.class);

            verify(notificationService, never()).notifyClientConfirmed(any());
        }

        @Test
        @DisplayName("❌ бронь не найдена → BookingNotFoundException")
        void confirmBooking_notFound_throwsException() {
            when(bookingRepository.findById(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.confirmBooking(9999L))
                    .isInstanceOf(BookingNotFoundException.class);
        }
    }

    // cancelByClient — отмена клиентом

    @Nested
    @DisplayName("cancelByClient")
    class CancelByClient {

        @Test
        @DisplayName("✅ отмена за 2 дня до начала — успех, слот освобождается")
        void cancelByClient_moreThan24Hours_success() {
            // Слот через 2 дня — можно отменить
            TimeSlot futureSlot = TimeSlot.builder()
                    .id(200L)
                    .date(LocalDate.now().plusDays(2))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(12, 0))
                    .status(SlotStatus.BOOKED)
                    .build();

            Booking booking = Booking.builder()
                    .id(2000L)
                    .slot(futureSlot)
                    .client(activeClient)
                    .pet(activePet)
                    .status(BookingStatus.CONFIRMED)
                    .gcalEventId("gcal-event-xyz")
                    .build();

            when(bookingRepository.findById(2000L)).thenReturn(Optional.of(booking));

            bookingService.cancelByClient(2000L, 100L);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_CLIENT);
            assertThat(futureSlot.getStatus()).isEqualTo(SlotStatus.FREE);

            verify(googleCalendarService).deleteEvent("gcal-event-xyz");
            verify(timeSlotRepository).save(futureSlot);
            verify(notificationService).notifyMasterClientCancelled(booking);
        }

        @Test
        @DisplayName("❌ отмена менее чем за 24 часа → CancellationTooLateException")
        void cancelByClient_lessThan24Hours_throwsException() {
            // Слот начинается через 2 часа — слишком поздно для отмены
            TimeSlot soonSlot = TimeSlot.builder()
                    .id(201L)
                    .date(LocalDate.now())
                    .startTime(LocalTime.now().plusHours(2))
                    .endTime(LocalTime.now().plusHours(4))
                    .status(SlotStatus.BOOKED)
                    .build();

            Booking booking = Booking.builder()
                    .id(2001L)
                    .slot(soonSlot)
                    .client(activeClient)
                    .pet(activePet)
                    .status(BookingStatus.CONFIRMED)
                    .build();

            when(bookingRepository.findById(2001L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelByClient(2001L, 100L))
                    .isInstanceOf(CancellationTooLateException.class);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED); // статус не изменился
            verify(notificationService, never()).notifyMasterClientCancelled(any());
        }

        @Test
        @DisplayName("❌ клиент пытается отменить чужую бронь → GroomBookException")
        void cancelByClient_foreignBooking_throwsException() {
            Booking booking = Booking.builder()
                    .id(2002L)
                    .slot(freeSlot)
                    .client(blockedClient) // принадлежит другому клиенту (telegramId=200)
                    .pet(activePet)
                    .status(BookingStatus.CONFIRMED)
                    .build();

            when(bookingRepository.findById(2002L)).thenReturn(Optional.of(booking));

            // Клиент с telegramId=100 пытается отменить бронь клиента 200
            assertThatThrownBy(() -> bookingService.cancelByClient(2002L, 100L))
                    .isInstanceOf(GroomBookException.class);
        }
    }

    // markNoShow — клиент не пришёл

    @Nested
    @DisplayName("markNoShow")
    class MarkNoShow {

        @Test
        @DisplayName("✅ no-show: статус брони, счётчик клиента, слот освобождается")
        void markNoShow_success() {
            when(bookingRepository.findById(1001L)).thenReturn(Optional.of(confirmedBooking));
            when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int noShowBefore = activeClient.getNoShowCount();

            bookingService.markNoShow(1001L);

            assertThat(confirmedBooking.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
            assertThat(confirmedBooking.isNoShow()).isTrue();
            assertThat(activeClient.getNoShowCount()).isEqualTo(noShowBefore + 1);
            assertThat(freeSlot.getStatus()).isEqualTo(SlotStatus.FREE);

            verify(googleCalendarService).deleteEvent("gcal-event-123");
            verify(clientRepository).save(activeClient);
        }

        @Test
        @DisplayName("❌ no-show для PENDING брони (ещё не подтверждена) → InvalidBookingStatusException")
        void markNoShow_pendingBooking_throwsException() {
            when(bookingRepository.findById(1000L)).thenReturn(Optional.of(pendingBooking));

            assertThatThrownBy(() -> bookingService.markNoShow(1000L))
                    .isInstanceOf(InvalidBookingStatusException.class);

            assertThat(activeClient.getNoShowCount()).isEqualTo(0);
            verify(clientRepository, never()).save(any());
        }
    }

    // completeBooking — завершение визита

    @Nested
    @DisplayName("completeBooking")
    class CompleteBooking {

        @Test
        @DisplayName("✅ CONFIRMED → COMPLETED с заметкой мастера")
        void completeBooking_success() {
            when(bookingRepository.findById(1001L)).thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Booking result = bookingService.completeBooking(1001L, "Отличная работа, пёс спокойный");

            assertThat(result.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(result.getMasterNote()).isEqualTo("Отличная работа, пёс спокойный");
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("✅ CONFIRMED → COMPLETED без заметки (null)")
        void completeBooking_withoutNote_success() {
            when(bookingRepository.findById(1001L)).thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Booking result = bookingService.completeBooking(1001L, null);

            assertThat(result.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(result.getMasterNote()).isNull();
        }

        @Test
        @DisplayName("❌ завершить PENDING бронь → InvalidBookingStatusException")
        void completeBooking_pendingStatus_throwsException() {
            when(bookingRepository.findById(1000L)).thenReturn(Optional.of(pendingBooking));

            assertThatThrownBy(() -> bookingService.completeBooking(1000L, null))
                    .isInstanceOf(InvalidBookingStatusException.class);
        }
    }

    // rejectBooking — отклонение мастером

    @Nested
    @DisplayName("rejectBooking")
    class RejectBooking {

        @Test
        @DisplayName("✅ PENDING → CANCELLED_BY_MASTER, слот освобождается, клиент уведомлён")
        void rejectBooking_success() {
            when(bookingRepository.findById(1000L)).thenReturn(Optional.of(pendingBooking));

            bookingService.rejectBooking(1000L, "Занят в этот день");

            assertThat(pendingBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_MASTER);
            assertThat(freeSlot.getStatus()).isEqualTo(SlotStatus.FREE);

            verify(timeSlotRepository).save(freeSlot);
            verify(notificationService).notifyClientCancelled(eq(pendingBooking), anyString());
        }

        @Test
        @DisplayName("❌ отклонить уже подтверждённую бронь → InvalidBookingStatusException")
        void rejectBooking_alreadyConfirmed_throwsException() {
            when(bookingRepository.findById(1001L)).thenReturn(Optional.of(confirmedBooking));

            assertThatThrownBy(() -> bookingService.rejectBooking(1001L, "причина"))
                    .isInstanceOf(InvalidBookingStatusException.class);

            verify(notificationService, never()).notifyClientCancelled(any(), any());
        }
    }

    // createManualBooking — договорная запись

    @Nested
    @DisplayName("createManualBooking")
    class CreateManualBooking {

        @Test
        @DisplayName("✅ договорная запись создаётся сразу CONFIRMED, без уведомления клиенту")
        void createManualBooking_success() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(googleCalendarService.createEvent(any())).thenReturn("gcal-manual-001");

            LocalDate date = LocalDate.now().plusDays(3);
            LocalTime time = LocalTime.of(18, 0);

            Booking result = bookingService.createManualBooking(
                    1L, 10L, date, time, 2, "Договорились лично");

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(result.getBookingType()).isEqualTo(BookingType.MANUAL);
            assertThat(result.getConfirmedAt()).isNotNull();

            // Клиент НЕ уведомляется — мастер уже договорился лично
            verify(notificationService, never()).notifyClientConfirmed(any());
            // Событие создаётся в Calendar
            verify(googleCalendarService).createEvent(any());
        }

        @Test
        @DisplayName("✅ ошибка Calendar не откатывает договорную запись")
        void createManualBooking_calendarFails_stillCreates() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(timeSlotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(googleCalendarService.createEvent(any()))
                    .thenThrow(new RuntimeException("Calendar API недоступен"));

            Booking result = bookingService.createManualBooking(
                    1L, 10L, LocalDate.now().plusDays(1), LocalTime.of(18, 0), 2, null);

            // Запись создана несмотря на ошибку Calendar
            assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(result.getGcalEventId()).isNull();
        }
    }

    // Запросы

    @Nested
    @DisplayName("Запросы")
    class Queries {

        @Test
        @DisplayName("getActiveBookingsForClient — возвращает только активные брони клиента")
        void getActiveBookingsForClient_returnsActiveBookings() {
            when(clientRepository.findByTelegramId(100L)).thenReturn(Optional.of(activeClient));
            when(bookingRepository.findActiveByClientId(1L))
                    .thenReturn(List.of(pendingBooking, confirmedBooking));

            List<Booking> result = bookingService.getActiveBookingsForClient(100L);

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(Booking::isActive);
        }

        @Test
        @DisplayName("getBookingsForDate — возвращает все брони на конкретный день")
        void getBookingsForDate_returnsBookingsForDay() {
            LocalDate today = LocalDate.now();
            when(bookingRepository.findByDate(today))
                    .thenReturn(List.of(pendingBooking));

            List<Booking> result = bookingService.getBookingsForDate(today);

            assertThat(result).hasSize(1);
            verify(bookingRepository).findByDate(today);
        }

        @Test
        @DisplayName("getActiveBookingsInRange — возвращает активные брони в диапазоне дат")
        void getActiveBookingsInRange_returnsBookingsInRange() {
            LocalDate from = LocalDate.now();
            LocalDate to = LocalDate.now().plusDays(7);
            when(bookingRepository.findActiveInDateRange(from, to))
                    .thenReturn(List.of(pendingBooking, confirmedBooking));

            List<Booking> result = bookingService.getActiveBookingsInRange(from, to);

            assertThat(result).hasSize(2);
            verify(bookingRepository).findActiveInDateRange(from, to);
        }
    }
}
