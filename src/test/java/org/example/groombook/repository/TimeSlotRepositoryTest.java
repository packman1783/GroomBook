package org.example.groombook.repository;

import org.example.groombook.BaseRepositoryTest;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.SlotStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeSlotRepository")
class TimeSlotRepositoryTest extends BaseRepositoryTest {

    @Autowired
    TimeSlotRepository timeSlotRepository;

    @BeforeEach
    void setUp() {
        timeSlotRepository.deleteAll();
    }

    // Уникальность (date, start_time)

    @Nested
    @DisplayName("uq_time_slots_date_start")
    class UniqueConstraint {

        @Test
        @DisplayName("❌ нельзя создать два слота в одно время на одну дату")
        void duplicateSlot_throwsException() {
            saveSlot(LocalDate.now().plusDays(1), LocalTime.of(10, 0));

            assertThatThrownBy(() ->
                    saveSlot(LocalDate.now().plusDays(1), LocalTime.of(10, 0)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("✅ одинаковое время на разные даты — разрешено")
        void sameTimeOnDifferentDates_allowed() {
            assertThatCode(() -> {
                saveSlot(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
                saveSlot(LocalDate.now().plusDays(2), LocalTime.of(10, 0));
            }).doesNotThrowAnyException();
        }
    }

    // findFreeSlotsBetween

    @Nested
    @DisplayName("findFreeSlotsBetween")
    class FindFreeSlotsBetween {

        @Test
        @DisplayName("✅ возвращает только FREE слоты в диапазоне")
        void findFreeSlotsBetween_mixedStatuses_returnsOnlyFree() {
            LocalDate d1 = LocalDate.now().plusDays(1);
            LocalDate d2 = LocalDate.now().plusDays(2);
            LocalDate d3 = LocalDate.now().plusDays(3);

            saveSlotWithStatus(d1, LocalTime.of(10, 0), SlotStatus.FREE);
            saveSlotWithStatus(d2, LocalTime.of(10, 0), SlotStatus.BOOKED);
            saveSlotWithStatus(d3, LocalTime.of(10, 0), SlotStatus.BLOCKED);
            saveSlotWithStatus(d1, LocalTime.of(12, 0), SlotStatus.FREE);

            List<TimeSlot> result = timeSlotRepository.findFreeSlotsBetween(
                    LocalDate.now().plusDays(1),
                    LocalDate.now().plusDays(5));

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(s -> s.getStatus() == SlotStatus.FREE);
        }

        @Test
        @DisplayName("✅ слоты вне диапазона не возвращаются")
        void findFreeSlotsBetween_outsideRange_notReturned() {
            saveSlot(LocalDate.now().plusDays(20), LocalTime.of(10, 0)); // за горизонтом

            List<TimeSlot> result = timeSlotRepository.findFreeSlotsBetween(
                    LocalDate.now().plusDays(1),
                    LocalDate.now().plusDays(14));

            assertThat(result).isEmpty();
        }
    }

    // deleteFreeGeneratedSlotsFrom

    @Nested
    @DisplayName("deleteFreeGeneratedSlotsFrom")
    class DeleteFreeGeneratedSlotsFrom {

        @Test
        @DisplayName("✅ удаляет FREE сгенерированные слоты начиная с даты")
        void deleteFreeGeneratedSlotsFrom_deletesCorrectSlots() {
            LocalDate cutoff = LocalDate.now().plusDays(3);

            saveSlot(LocalDate.now().plusDays(1), LocalTime.of(10, 0)); // до cutoff — остаётся
            saveSlot(LocalDate.now().plusDays(2), LocalTime.of(10, 0)); // до cutoff — остаётся
            saveSlot(cutoff, LocalTime.of(10, 0)); // с cutoff — удаляется
            saveSlot(LocalDate.now().plusDays(5), LocalTime.of(10, 0)); // после — удаляется

            timeSlotRepository.deleteFreeGeneratedSlotsFrom(cutoff);

            List<TimeSlot> remaining = timeSlotRepository.findAll();
            assertThat(remaining).hasSize(2);
            assertThat(remaining).allMatch(s -> s.getDate().isBefore(cutoff));
        }

        @Test
        @DisplayName("✅ BOOKED слоты не удаляются несмотря на дату")
        void deleteFreeGeneratedSlotsFrom_keepsBoooked() {
            LocalDate future = LocalDate.now().plusDays(5);
            saveSlotWithStatus(future, LocalTime.of(10, 0), SlotStatus.BOOKED);  // не удаляется
            saveSlotWithStatus(future, LocalTime.of(12, 0), SlotStatus.FREE);    // удаляется

            timeSlotRepository.deleteFreeGeneratedSlotsFrom(future);

            List<TimeSlot> remaining = timeSlotRepository.findAll();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.getFirst().getStatus()).isEqualTo(SlotStatus.BOOKED);
        }

        @Test
        @DisplayName("✅ ручные (is_manual=true) FREE слоты не удаляются")
        void deleteFreeGeneratedSlotsFrom_keepsManualSlots() {
            LocalDate future = LocalDate.now().plusDays(5);
            // Договорная запись — вручную, не трогаем
            timeSlotRepository.save(TimeSlot.builder()
                    .date(future)
                    .startTime(LocalTime.of(18, 0))
                    .endTime(LocalTime.of(20, 0))
                    .status(SlotStatus.FREE)
                    .manual(true) // ← ручной
                    .build());

            saveSlot(future, LocalTime.of(10, 0)); // сгенерированный — удаляется

            timeSlotRepository.deleteFreeGeneratedSlotsFrom(future);

            List<TimeSlot> remaining = timeSlotRepository.findAll();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.getFirst().isManual()).isTrue();
        }
    }

    // findMaxGeneratedDate

    @Nested
    @DisplayName("findMaxGeneratedDate")
    class FindMaxGeneratedDate {

        @Test
        @DisplayName("✅ возвращает самую позднюю дату среди сгенерированных слотов")
        void findMaxGeneratedDate_returnsLatestDate() {
            saveSlot(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
            saveSlot(LocalDate.now().plusDays(5), LocalTime.of(10, 0));
            saveSlot(LocalDate.now().plusDays(3), LocalTime.of(10, 0));

            Optional<LocalDate> max = timeSlotRepository.findMaxGeneratedDate();

            assertThat(max).isPresent();
            assertThat(max.get()).isEqualTo(LocalDate.now().plusDays(5));
        }

        @Test
        @DisplayName("✅ ручные слоты не учитываются в максимуме")
        void findMaxGeneratedDate_ignoresManualSlots() {
            saveSlot(LocalDate.now().plusDays(3), LocalTime.of(10, 0)); // сгенерированный
            // Ручной слот с более поздней датой — не должен учитываться
            timeSlotRepository.save(TimeSlot.builder()
                    .date(LocalDate.now().plusDays(10))
                    .startTime(LocalTime.of(18, 0))
                    .endTime(LocalTime.of(20, 0))
                    .status(SlotStatus.MANUAL_BOOKING)
                    .manual(true)
                    .build());

            Optional<LocalDate> max = timeSlotRepository.findMaxGeneratedDate();

            assertThat(max).isPresent();
            assertThat(max.get()).isEqualTo(LocalDate.now().plusDays(3));
        }

        @Test
        @DisplayName("✅ пустая таблица — empty")
        void findMaxGeneratedDate_emptyTable_empty() {
            Optional<LocalDate> max = timeSlotRepository.findMaxGeneratedDate();

            assertThat(max).isEmpty();
        }
    }

    // existsByDateAndStatus

    @Nested
    @DisplayName("existsByDateAndStatus")
    class ExistsByDateAndStatus {

        @Test
        @DisplayName("✅ есть FREE слот на дату → true")
        void existsByDateAndStatus_freeSlotExists_returnsTrue() {
            LocalDate date = LocalDate.now().plusDays(1);
            saveSlotWithStatus(date, LocalTime.of(10, 0), SlotStatus.FREE);

            assertThat(timeSlotRepository.existsByDateAndStatus(date, SlotStatus.FREE)).isTrue();
        }

        @Test
        @DisplayName("✅ нет FREE слотов (только BOOKED) → false")
        void existsByDateAndStatus_noFreeSlots_returnsFalse() {
            LocalDate date = LocalDate.now().plusDays(1);
            saveSlotWithStatus(date, LocalTime.of(10, 0), SlotStatus.BOOKED);

            assertThat(timeSlotRepository.existsByDateAndStatus(date, SlotStatus.FREE)).isFalse();
        }
    }

    // Вспомогательные методы

    private TimeSlot saveSlot(LocalDate date, LocalTime startTime) {
        return saveSlotWithStatus(date, startTime, SlotStatus.FREE);
    }

    private TimeSlot saveSlotWithStatus(LocalDate date, LocalTime startTime, SlotStatus status) {
        return timeSlotRepository.save(TimeSlot.builder()
                .date(date)
                .startTime(startTime)
                .endTime(startTime.plusHours(2))
                .status(status)
                .manual(false)
                .build());
    }
}
