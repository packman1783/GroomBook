package org.example.groombook.service;

import org.example.groombook.exception.GroomBookException;
import org.example.groombook.exception.NoActiveTemplateException;
import org.example.groombook.exception.SlotNotFoundException;
import org.example.groombook.exception.TemplateNotFoundException;
import org.example.groombook.model.DayOverride;
import org.example.groombook.model.ScheduleTemplate;
import org.example.groombook.model.TemplateDay;
import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.OverrideType;
import org.example.groombook.model.enums.SlotStatus;
import org.example.groombook.repository.DayOverrideRepository;
import org.example.groombook.repository.ScheduleTemplateRepository;
import org.example.groombook.repository.TimeSlotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService")
class ScheduleServiceTest {

    @Mock ScheduleTemplateRepository templateRepository;
    @Mock DayOverrideRepository      overrideRepository;
    @Mock TimeSlotRepository         timeSlotRepository;

    @InjectMocks
    ScheduleService scheduleService;

    // ── Общие тестовые данные ─────────────────────────────────────────────────

    /** Шаблон: Пн–Пт рабочие 09:00–19:00, слот 2 часа */
    private ScheduleTemplate activeTemplate;

    @BeforeEach
    void setUp() {
        activeTemplate = buildTemplate(1L, "Стандартный", true, 2);
    }

    // generateSlotsForDay — генерация слотов на один день

    @Nested
    @DisplayName("generateSlotsForDay")
    class GenerateSlotsForDay {

        @Test
        @DisplayName("✅ рабочий день — 5 слотов по 2 часа (09:00–19:00)")
        void generateSlotsForDay_workingDay_createsCorrectSlots() {
            // Понедельник
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = scheduleService.generateSlotsForDay(monday);

            // 09:00–11:00, 11:00–13:00, 13:00–15:00, 15:00–17:00, 17:00–19:00
            assertThat(count).isEqualTo(5);

            ArgumentCaptor<List<TimeSlot>> captor = ArgumentCaptor.captor();
            verify(timeSlotRepository).saveAll(captor.capture());

            List<TimeSlot> saved = captor.getValue();
            assertThat(saved).hasSize(5);
            assertThat(saved.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(saved.get(0).getEndTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(saved.get(4).getStartTime()).isEqualTo(LocalTime.of(17, 0));
            assertThat(saved.get(4).getEndTime()).isEqualTo(LocalTime.of(19, 0));
            assertThat(saved).allMatch(s -> s.getStatus() == SlotStatus.FREE);
            assertThat(saved).allMatch(s -> !s.isManual());
        }

        @Test
        @DisplayName("✅ воскресенье (выходной) — 0 слотов")
        void generateSlotsForDay_weekend_noSlots() {
            LocalDate sunday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY));

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(sunday)).thenReturn(Optional.empty());

            int count = scheduleService.generateSlotsForDay(sunday);

            assertThat(count).isEqualTo(0);
            verify(timeSlotRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("✅ день переопределён как HOLIDAY — 0 слотов несмотря на шаблон")
        void generateSlotsForDay_holidayOverride_noSlots() {
            // Понедельник рабочий по шаблону, но переопределён как праздник
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            DayOverride holiday = DayOverride.holiday(monday, "9 мая");

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.of(holiday));

            int count = scheduleService.generateSlotsForDay(monday);

            assertThat(count).isEqualTo(0);
            verify(timeSlotRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("✅ день переопределён с сокращёнными часами — только 2 слота (09:00–13:00)")
        void generateSlotsForDay_customHoursOverride_fewerSlots() {
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            // Рабочий только до обеда — 09:00–13:00
            DayOverride shortDay = DayOverride.customHours(
                    monday, LocalTime.of(9, 0), LocalTime.of(13, 0), "Врач после обеда");

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.of(shortDay));
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = scheduleService.generateSlotsForDay(monday);

            // 09:00–11:00 и 11:00–13:00 = 2 слота
            assertThat(count).isEqualTo(2);

            ArgumentCaptor<List<TimeSlot>> captor = ArgumentCaptor.captor();
            verify(timeSlotRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("✅ выходной переопределён как EXTRA_WORKING_DAY — 3 слота (10:00–16:00)")
        void generateSlotsForDay_extraWorkingDay_createsSlots() {
            LocalDate sunday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY));

            DayOverride extraDay = DayOverride.extraWorkingDay(
                    sunday, LocalTime.of(10, 0), LocalTime.of(16, 0));

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(sunday)).thenReturn(Optional.of(extraDay));
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = scheduleService.generateSlotsForDay(sunday);

            // 10:00–12:00, 12:00–14:00, 14:00–16:00 = 3 слота
            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("✅ слоты уже существуют — не создаются повторно (защита от дублей)")
        void generateSlotsForDay_slotsAlreadyExist_skipsExisting() {
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.empty());
            // Все слоты уже существуют
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(true);

            int count = scheduleService.generateSlotsForDay(monday);

            assertThat(count).isEqualTo(0);
            verify(timeSlotRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("✅ только часть слотов существует — создаются только недостающие")
        void generateSlotsForDay_someExist_createsOnlyMissing() {
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.empty());
            // Первые 3 слота уже есть, последние 2 — нет
            when(timeSlotRepository.existsByDateAndStartTime(eq(monday), eq(LocalTime.of(9,  0)))).thenReturn(true);
            when(timeSlotRepository.existsByDateAndStartTime(eq(monday), eq(LocalTime.of(11, 0)))).thenReturn(true);
            when(timeSlotRepository.existsByDateAndStartTime(eq(monday), eq(LocalTime.of(13, 0)))).thenReturn(true);
            when(timeSlotRepository.existsByDateAndStartTime(eq(monday), eq(LocalTime.of(15, 0)))).thenReturn(false);
            when(timeSlotRepository.existsByDateAndStartTime(eq(monday), eq(LocalTime.of(17, 0)))).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = scheduleService.generateSlotsForDay(monday);

            assertThat(count).isEqualTo(2);

            ArgumentCaptor<List<TimeSlot>> captor = ArgumentCaptor.captor();
            verify(timeSlotRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            assertThat(captor.getValue().get(0).getStartTime()).isEqualTo(LocalTime.of(15, 0));
            assertThat(captor.getValue().get(1).getStartTime()).isEqualTo(LocalTime.of(17, 0));
        }

        @Test
        @DisplayName("✅ шаблон с 1-часовым слотом — 10 слотов (09:00–19:00)")
        void generateSlotsForDay_oneHourSlot_tenSlots() {
            ScheduleTemplate oneHourTemplate = buildTemplate(2L, "Быстрый", true, 1);
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

            when(templateRepository.findActive()).thenReturn(Optional.of(oneHourTemplate));
            when(overrideRepository.findByDate(monday)).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int count = scheduleService.generateSlotsForDay(monday);

            assertThat(count).isEqualTo(10);
        }

        @Test
        @DisplayName("❌ нет активного шаблона → NoActiveTemplateException")
        void generateSlotsForDay_noActiveTemplate_throwsException() {
            when(templateRepository.findActive()).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    scheduleService.generateSlotsForDay(LocalDate.now().plusDays(1)))
                    .isInstanceOf(NoActiveTemplateException.class);
        }

        @Test
        @DisplayName("✅ шаблон не применим к дате (до active_from) — 0 слотов")
        void generateSlotsForDay_templateNotApplicable_noSlots() {
            ScheduleTemplate futureTemplate = buildTemplate(3L, "Будущий", true, 2);
            // Шаблон начинается только через 30 дней
            futureTemplate.setActiveFrom(LocalDate.now().plusDays(30));

            when(templateRepository.findActive()).thenReturn(Optional.of(futureTemplate));

            int count = scheduleService.generateSlotsForDay(LocalDate.now().plusDays(1));

            assertThat(count).isEqualTo(0);
            verify(timeSlotRepository, never()).saveAll(any());
        }
    }

    // generateSlotsForRange — генерация на диапазон дат

    @Nested
    @DisplayName("generateSlotsForRange")
    class GenerateSlotsForRange {

        @Test
        @DisplayName("✅ диапазон Пн–Вс: 5 рабочих дней × 5 слотов = 25 слотов")
        void generateSlotsForRange_oneWeek_correctTotal() {
            LocalDate monday = LocalDate.now().with(
                    TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
            LocalDate sunday = monday.plusDays(6);

            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(any())).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            int total = scheduleService.generateSlotsForRange(monday, sunday);

            // Пн, Вт, Ср, Чт, Пт — по 5 слотов; Сб, Вс — выходные
            assertThat(total).isEqualTo(25);
            // saveAll вызывается для каждого рабочего дня
            verify(timeSlotRepository, times(5)).saveAll(any());
        }
    }

    // activateTemplate — переключение активного шаблона

    @Nested
    @DisplayName("activateTemplate")
    class ActivateTemplate {

        @Test
        @DisplayName("✅ старый шаблон деактивируется, новый активируется, FREE-слоты пересоздаются")
        void activateTemplate_success() {
            ScheduleTemplate oldTemplate = buildTemplate(1L, "Старый", true, 2);
            ScheduleTemplate newTemplate = buildTemplate(2L, "Новый", false, 2);
            LocalDate from = LocalDate.now().plusDays(1);

            when(templateRepository.findById(2L)).thenReturn(Optional.of(newTemplate));
            when(templateRepository.findActive()).thenReturn(Optional.of(oldTemplate));
            when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(overrideRepository.findByDate(any())).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            scheduleService.activateTemplate(2L, from, null);

            // Старый шаблон деактивирован
            assertThat(oldTemplate.isActive()).isFalse();
            // Новый шаблон активирован
            assertThat(newTemplate.isActive()).isTrue();
            assertThat(newTemplate.getActiveFrom()).isEqualTo(from);
            assertThat(newTemplate.getActiveUntil()).isNull();

            // FREE-слоты удалены и пересозданы
            verify(timeSlotRepository).deleteFreeGeneratedSlotsFrom(from);
        }

        @Test
        @DisplayName("✅ нет текущего активного шаблона — новый просто активируется")
        void activateTemplate_noCurrentActive_success() {
            ScheduleTemplate newTemplate = buildTemplate(2L, "Первый", false, 2);
            LocalDate from = LocalDate.now();

            when(templateRepository.findById(2L)).thenReturn(Optional.of(newTemplate));

            when(templateRepository.findActive())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(newTemplate));

            when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(overrideRepository.findByDate(any())).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            scheduleService.activateTemplate(2L, from, null);

            assertThat(newTemplate.isActive()).isTrue();
            verify(timeSlotRepository).deleteFreeGeneratedSlotsFrom(from);
        }

        @Test
        @DisplayName("❌ шаблон не найден → TemplateNotFoundException")
        void activateTemplate_notFound_throwsException() {
            when(templateRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    scheduleService.activateTemplate(999L, LocalDate.now(), null))
                    .isInstanceOf(TemplateNotFoundException.class);
        }
    }

    // blockSlot — блокировка конкретного слота

    @Nested
    @DisplayName("blockSlot")
    class BlockSlot {

        @Test
        @DisplayName("✅ FREE слот → BLOCKED с причиной")
        void blockSlot_freeSlot_becomesBlocked() {
            TimeSlot slot = TimeSlot.builder()
                    .id(1L)
                    .date(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(12, 0))
                    .status(SlotStatus.FREE)
                    .build();

            when(timeSlotRepository.findById(1L)).thenReturn(Optional.of(slot));

            scheduleService.blockSlot(1L, "Стоматолог");

            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BLOCKED);
            assertThat(slot.getBlockReason()).isEqualTo("Стоматолог");
            verify(timeSlotRepository).save(slot);
        }

        @Test
        @DisplayName("❌ BOOKED слот нельзя заблокировать → GroomBookException")
        void blockSlot_bookedSlot_throwsException() {
            TimeSlot slot = TimeSlot.builder()
                    .id(2L)
                    .date(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(12, 0))
                    .endTime(LocalTime.of(14, 0))
                    .status(SlotStatus.BOOKED)
                    .build();

            when(timeSlotRepository.findById(2L)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> scheduleService.blockSlot(2L, "причина"))
                    .isInstanceOf(GroomBookException.class)
                    .hasMessageContaining("активной бронью");

            // Статус слота не изменился
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
            verify(timeSlotRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ слот не найден → SlotNotFoundException")
        void blockSlot_notFound_throwsException() {
            when(timeSlotRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> scheduleService.blockSlot(999L, "причина"))
                    .isInstanceOf(SlotNotFoundException.class);
        }
    }

    // unblockSlot — снятие блокировки

    @Nested
    @DisplayName("unblockSlot")
    class UnblockSlot {

        @Test
        @DisplayName("✅ BLOCKED → FREE, причина очищается")
        void unblockSlot_success() {
            TimeSlot slot = TimeSlot.builder()
                    .id(1L)
                    .status(SlotStatus.BLOCKED)
                    .blockReason("Стоматолог")
                    .date(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(12, 0))
                    .build();

            when(timeSlotRepository.findById(1L)).thenReturn(Optional.of(slot));

            scheduleService.unblockSlot(1L);

            assertThat(slot.getStatus()).isEqualTo(SlotStatus.FREE);
            assertThat(slot.getBlockReason()).isNull();
            verify(timeSlotRepository).save(slot);
        }

        @Test
        @DisplayName("❌ попытка разблокировать FREE слот → GroomBookException")
        void unblockSlot_notBlocked_throwsException() {
            TimeSlot slot = TimeSlot.builder()
                    .id(2L)
                    .status(SlotStatus.FREE)
                    .date(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(12, 0))
                    .build();

            when(timeSlotRepository.findById(2L)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> scheduleService.unblockSlot(2L))
                    .isInstanceOf(GroomBookException.class);
        }
    }

    // blockDateRange — блокировка периода (отпуск)

    @Nested
    @DisplayName("blockDateRange")
    class BlockDateRange {

        @Test
        @DisplayName("✅ 3-дневный период — 3 override-записи, 3 удаления FREE-слотов")
        void blockDateRange_threeDays_createsOverridesAndDeletesSlots() {
            LocalDate from = LocalDate.now().plusDays(10);
            LocalDate to   = LocalDate.now().plusDays(12);

            when(overrideRepository.existsByDate(any())).thenReturn(false);
            when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            scheduleService.blockDateRange(from, to, OverrideType.VACATION, "Отпуск");

            // Три override + три удаления слотов
            verify(overrideRepository, times(3)).save(any());
            verify(timeSlotRepository, times(3)).deleteFreeSlotsByDate(any());
        }

        @Test
        @DisplayName("✅ дата с уже существующим override — не перезаписывается")
        void blockDateRange_existingOverride_skipsDate() {
            LocalDate date = LocalDate.now().plusDays(5);

            // Override уже есть на эту дату
            when(overrideRepository.existsByDate(date)).thenReturn(true);

            scheduleService.blockDateRange(date, date, OverrideType.VACATION, "Отпуск");

            // Override не перезаписывается, но FREE-слоты всё равно удаляются
            verify(overrideRepository, never()).save(any());
            verify(timeSlotRepository).deleteFreeSlotsByDate(date);
        }
    }

    // getAvailableDates — даты с FREE-слотами

    @Nested
    @DisplayName("getAvailableDates")
    class GetAvailableDates {

        @Test
        @DisplayName("✅ возвращает отсортированный список дат где есть FREE-слоты")
        void getAvailableDates_returnsSortedDates() {
            LocalDate d1 = LocalDate.now().plusDays(3);
            LocalDate d2 = LocalDate.now().plusDays(1);
            LocalDate d3 = LocalDate.now().plusDays(5);

            List<TimeSlot> freeSlots = List.of(
                    slotOn(d1), slotOn(d1), // два слота на одну дату — должна быть одна строка
                    slotOn(d2),
                    slotOn(d3)
            );

            when(timeSlotRepository.findFreeSlotsBetween(any(), any())).thenReturn(freeSlots);

            List<LocalDate> dates = scheduleService.getAvailableDates();

            assertThat(dates).containsExactly(d2, d1, d3); // отсортированы
            assertThat(dates).hasSize(3);                    // дубль убран
        }

        @Test
        @DisplayName("✅ нет свободных слотов — пустой список")
        void getAvailableDates_noFreeSlots_returnsEmpty() {
            when(timeSlotRepository.findFreeSlotsBetween(any(), any())).thenReturn(List.of());

            List<LocalDate> dates = scheduleService.getAvailableDates();

            assertThat(dates).isEmpty();
        }
    }

    // generateNextDay — планировщик

    @Nested
    @DisplayName("generateNextDay")
    class GenerateNextDay {

        @Test
        @DisplayName("✅ горизонт не заполнен — генерирует следующий день")
        void generateNextDay_horizonNotFull_generatesNextDay() {
            // Максимальная дата в БД — 5 дней вперёд (до горизонта 14 дней ещё есть место)
            LocalDate maxDate = LocalDate.now().plusDays(5);
            when(timeSlotRepository.findMaxGeneratedDate()).thenReturn(Optional.of(maxDate));
            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(any())).thenReturn(Optional.empty());
            when(timeSlotRepository.existsByDateAndStartTime(any(), any())).thenReturn(false);
            when(timeSlotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            scheduleService.generateNextDay();

            // Должны были попробовать сгенерировать день maxDate + 1
            verify(templateRepository).findActive();
        }

        @Test
        @DisplayName("✅ горизонт уже заполнен — ничего не генерируется")
        void generateNextDay_horizonFull_doesNothing() {
            // Максимальная дата — уже 20 дней вперёд (больше горизонта 14 дней)
            LocalDate maxDate = LocalDate.now().plusDays(20);
            when(timeSlotRepository.findMaxGeneratedDate()).thenReturn(Optional.of(maxDate));

            scheduleService.generateNextDay();

            // Шаблон не запрашивается — нечего генерировать
            verify(templateRepository, never()).findActive();
        }

        @Test
        @DisplayName("✅ БД пустая (нет слотов) — начинает с завтра")
        void generateNextDay_emptyDb_startsFromTomorrow() {
            when(timeSlotRepository.findMaxGeneratedDate()).thenReturn(Optional.empty());
            when(templateRepository.findActive()).thenReturn(Optional.of(activeTemplate));
            when(overrideRepository.findByDate(any())).thenReturn(Optional.empty());

            scheduleService.generateNextDay();

            verify(templateRepository).findActive();
        }
    }

    // Вспомогательные методы

    /**
     * Строит тестовый шаблон расписания.
     * Пн–Пт: рабочие 09:00–19:00
     * Сб, Вс: выходные
     */
    private ScheduleTemplate buildTemplate(Long id, String name, boolean active, int slotHours) {
        ScheduleTemplate template = ScheduleTemplate.builder()
                .id(id)
                .name(name)
                .active(active)
                .slotDurationHours(slotHours)
                .days(new java.util.ArrayList<>())
                .build();

        for (int dow = 1; dow <= 7; dow++) {
            boolean isWorking = dow <= 5; // Пн=1..Пт=5 рабочие, Сб=6 Вс=7 выходные
            TemplateDay day = TemplateDay.builder()
                    .id((long) dow)
                    .template(template)
                    .dayOfWeek(dow)
                    .working(isWorking)
                    .startTime(isWorking ? LocalTime.of(9, 0) : null)
                    .endTime(isWorking ? LocalTime.of(19, 0) : null)
                    .build();
            template.getDays().add(day);
        }

        return template;
    }

    private TimeSlot slotOn(LocalDate date) {
        return TimeSlot.builder()
                .date(date)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 0))
                .status(SlotStatus.FREE)
                .build();
    }
}
