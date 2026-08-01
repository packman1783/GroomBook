package org.example.groombook.repository;

import org.example.groombook.BaseRepositoryTest;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BookingRepository")
class BookingRepositoryTest extends BaseRepositoryTest {

    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    TimeSlotRepository timeSlotRepository;
    @Autowired
    ClientRepository clientRepository;
    @Autowired
    PetRepository petRepository;

    private Client client;
    private Pet pet;
    private TimeSlot slot;

    @BeforeEach
    void setUp() {
        // Чистим таблицы в правильном порядке (из-за FK)
        bookingRepository.deleteAll();
        timeSlotRepository.deleteAll();
        petRepository.deleteAll();
        clientRepository.deleteAll();

        client = clientRepository.save(Client.builder()
                .telegramId(100L)
                .name("Иван Петров")
                .phone("+79991234567")
                .status(ClientStatus.ACTIVE)
                .noShowCount(0)
                .build());

        pet = petRepository.save(Pet.builder()
                .client(client)
                .name("Рекс")
                .type(PetType.DOG)
                .difficulty(PetDifficulty.EASY)
                .active(true)
                .build());

        slot = timeSlotRepository.save(TimeSlot.builder()
                .date(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 0))
                .status(SlotStatus.FREE)
                .manual(false)
                .build());
    }

    // Partial unique index — главная защита от двойного бронирования

    @Nested
    @DisplayName("idx_one_active_booking_per_slot")
    class PartialUniqueIndex {

        @Test
        @DisplayName("❌ нельзя создать две PENDING брони на один слот")
        void twoPendingBookingsOnSameSlot_throwsException() {
            saveBookingWithStatus(BookingStatus.PENDING);

            assertThatThrownBy(() -> saveBookingWithStatus(BookingStatus.PENDING))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("❌ нельзя создать PENDING + CONFIRMED на один слот")
        void pendingAndConfirmedOnSameSlot_throwsException() {
            saveBookingWithStatus(BookingStatus.PENDING);

            assertThatThrownBy(() -> saveBookingWithStatus(BookingStatus.CONFIRMED))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("✅ CANCELLED + новая PENDING на тот же слот — разрешено")
        void cancelledAndNewPendingOnSameSlot_allowed() {
            Booking first = saveBookingWithStatus(BookingStatus.PENDING);
            first.cancelByClient();
            bookingRepository.saveAndFlush(first);

            // После отмены можно создать новую бронь на тот же слот
            assertThatCode(() -> saveBookingWithStatus(BookingStatus.PENDING))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ COMPLETED + новая PENDING на тот же слот — разрешено")
        void completedAndNewPendingOnSameSlot_allowed() {
            Booking first = saveBookingWithStatus(BookingStatus.CONFIRMED);
            first.complete(null);
            bookingRepository.saveAndFlush(first);

            assertThatCode(() -> saveBookingWithStatus(BookingStatus.PENDING))
                    .doesNotThrowAnyException();
        }
    }

    // findActiveBySlotId

    @Nested
    @DisplayName("findActiveBySlotId")
    class FindActiveBySlotId {

        @Test
        @DisplayName("✅ находит PENDING бронь по слоту")
        void findActiveBySlotId_pendingBooking_found() {
            saveBookingWithStatus(BookingStatus.PENDING);

            Optional<Booking> result = bookingRepository.findActiveBySlotId(slot.getId());

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(BookingStatus.PENDING);
        }

        @Test
        @DisplayName("✅ находит CONFIRMED бронь по слоту")
        void findActiveBySlotId_confirmedBooking_found() {
            saveBookingWithStatus(BookingStatus.CONFIRMED);

            Optional<Booking> result = bookingRepository.findActiveBySlotId(slot.getId());

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("✅ CANCELLED бронь — не возвращается как активная")
        void findActiveBySlotId_cancelledBooking_notFound() {
            Booking b = saveBookingWithStatus(BookingStatus.PENDING);
            b.cancelByClient();
            bookingRepository.save(b);

            Optional<Booking> result = bookingRepository.findActiveBySlotId(slot.getId());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("✅ нет брони на слот — empty")
        void findActiveBySlotId_noBooking_empty() {
            Optional<Booking> result = bookingRepository.findActiveBySlotId(slot.getId());

            assertThat(result).isEmpty();
        }
    }

    // findActiveByClientId

    @Nested
    @DisplayName("findActiveByClientId")
    class FindActiveByClientId {

        @Test
        @DisplayName("✅ возвращает только PENDING и CONFIRMED брони клиента")
        void findActiveByClientId_mixedStatuses_returnsOnlyActive() {
            // Создаём слоты для каждой брони
            TimeSlot slot2 = saveSlotAt(LocalTime.of(12, 0));
            TimeSlot slot3 = saveSlotAt(LocalTime.of(14, 0));
            TimeSlot slot4 = saveSlotAt(LocalTime.of(16, 0));

            saveBookingForSlot(slot, BookingStatus.PENDING);
            saveBookingForSlot(slot2, BookingStatus.CONFIRMED);

            Booking cancelled = saveBookingForSlot(slot3, BookingStatus.PENDING);
            cancelled.cancelByClient();
            bookingRepository.save(cancelled);

            Booking completed = saveBookingForSlot(slot4, BookingStatus.CONFIRMED);
            completed.complete(null);
            bookingRepository.save(completed);

            List<Booking> result = bookingRepository.findActiveByClientId(client.getId());

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(Booking::isActive);
        }

        @Test
        @DisplayName("✅ нет активных броней — пустой список")
        void findActiveByClientId_noActiveBookings_empty() {
            List<Booking> result = bookingRepository.findActiveByClientId(client.getId());

            assertThat(result).isEmpty();
        }
    }

    // countActiveByClientInWeek

    @Nested
    @DisplayName("countActiveByClientInWeek")
    class CountActiveByClientInWeek {

        @Test
        @DisplayName("✅ 2 брони на этой неделе — count = 2")
        void countActiveByClientInWeek_twoBookings_returnsTwo() {
            TimeSlot slot2 = saveSlotAt(LocalTime.of(12, 0));
            saveBookingForSlot(slot, BookingStatus.PENDING);
            saveBookingForSlot(slot2, BookingStatus.CONFIRMED);

            LocalDateTime weekStart = LocalDateTime.now()
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(
                            java.time.DayOfWeek.MONDAY))
                    .toLocalDate().atStartOfDay();
            LocalDateTime weekEnd = weekStart.plusDays(7);

            long count = bookingRepository.countActiveByClientInWeek(
                    client.getId(), weekStart, weekEnd);

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("✅ CANCELLED не считается в лимит недели")
        void countActiveByClientInWeek_cancelledNotCounted() {
            Booking b = saveBookingForSlot(slot, BookingStatus.PENDING);
            b.cancelByClient();
            bookingRepository.save(b);

            LocalDateTime weekStart = LocalDateTime.now()
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(
                            java.time.DayOfWeek.MONDAY))
                    .toLocalDate().atStartOfDay();

            long count = bookingRepository.countActiveByClientInWeek(
                    client.getId(), weekStart, weekStart.plusDays(7));

            assertThat(count).isEqualTo(0);
        }
    }

    // findByDate

    @Nested
    @DisplayName("findByDate")
    class FindByDate {

        @Test
        @DisplayName("✅ возвращает брони на конкретный день, отсортированные по времени")
        void findByDate_returnsBookingsSortedByTime() {
            TimeSlot earlySlot = saveSlotAt(LocalTime.of(9, 0));
            TimeSlot lateSlot = saveSlotAt(LocalTime.of(14, 0));

            saveBookingForSlot(lateSlot, BookingStatus.CONFIRMED);
            saveBookingForSlot(earlySlot, BookingStatus.PENDING);

            List<Booking> result = bookingRepository.findByDate(LocalDate.now().plusDays(1));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getSlot().getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(result.get(1).getSlot().getStartTime()).isEqualTo(LocalTime.of(14, 0));
        }

        @Test
        @DisplayName("✅ брони на другой день не попадают в результат")
        void findByDate_otherDayBookings_notIncluded() {
            saveBookingForSlot(slot, BookingStatus.PENDING);

            // Ищем брони на послезавтра — там ничего нет
            List<Booking> result = bookingRepository.findByDate(LocalDate.now().plusDays(2));

            assertThat(result).isEmpty();
        }
    }

    // findActiveInDateRange

    @Nested
    @DisplayName("findActiveInDateRange")
    class FindActiveInDateRange {

        @Test
        @DisplayName("✅ находит активные брони в диапазоне дат (для предупреждения при отпуске)")
        void findActiveInDateRange_returnsBookingsInRange() {
            TimeSlot slotD3 = timeSlotRepository.save(TimeSlot.builder()
                    .date(LocalDate.now().plusDays(3))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(12, 0))
                    .status(SlotStatus.FREE)
                    .manual(false)
                    .build());

            saveBookingForSlot(slot, BookingStatus.CONFIRMED); // +1 день — в диапазоне
            saveBookingForSlot(slotD3, BookingStatus.PENDING);  // +3 дня — в диапазоне

            List<Booking> result = bookingRepository.findActiveInDateRange(
                    LocalDate.now().plusDays(1),
                    LocalDate.now().plusDays(5));

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("✅ COMPLETED брони не попадают в диапазон")
        void findActiveInDateRange_completedNotIncluded() {
            Booking b = saveBookingForSlot(slot, BookingStatus.CONFIRMED);
            b.complete(null);
            bookingRepository.save(b);

            List<Booking> result = bookingRepository.findActiveInDateRange(
                    LocalDate.now().plusDays(1),
                    LocalDate.now().plusDays(5));

            assertThat(result).isEmpty();
        }
    }

    // Вспомогательные методы

    private Booking saveBookingWithStatus(BookingStatus status) {
        return saveBookingForSlot(slot, status);
    }

    private Booking saveBookingForSlot(TimeSlot forSlot, BookingStatus status) {
        return bookingRepository.save(Booking.builder()
                .slot(forSlot)
                .client(client)
                .pet(pet)
                .bookingType(BookingType.STANDARD)
                .status(status)
                .build());
    }

    private TimeSlot saveSlotAt(LocalTime startTime) {
        return timeSlotRepository.save(TimeSlot.builder()
                .date(LocalDate.now().plusDays(1))
                .startTime(startTime)
                .endTime(startTime.plusHours(2))
                .status(SlotStatus.FREE)
                .manual(false)
                .build());
    }
}
