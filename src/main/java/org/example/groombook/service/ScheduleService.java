package org.example.groombook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;

/**
 * Сервис управления генерацией и переопределением расписания (слотов времени).
 * <p>
 * Реализует следующую ключевую функциональность:
 * <ul>
 *   <li>Управление недельными шаблонами работы ({@link ScheduleTemplate}).</li>
 *   <li>Автоматическую и ручную генерацию слотов на заданный горизонт (по умолчанию {@value #BOOKING_HORIZON_DAYS} дней).</li>
 *   <li>Обработку переопределений дней ({@link DayOverride}) — праздники, изменения часов, блокировки периода/отпуска.</li>
 *   <li>Ручную точечную блокировку/разблокировку индивидуальных слотов.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /**
     * Горизонт бронирования (в днях) — глубина, на которую система автоматически генерирует и поддерживает свободные слоты.
     */
    private static final int BOOKING_HORIZON_DAYS = 14;

    private final ScheduleTemplateRepository templateRepository;
    private final DayOverrideRepository overrideRepository;
    private final TimeSlotRepository timeSlotRepository;

    // Управление шаблонами

    /**
     * Активирует указанный шаблон расписания.
     * <p>
     * Деактивирует текущий используемый шаблон. С даты начала действия нового шаблона {@code from}
     * каскадно удаляются все ранее сгенерированные не забронированные слоты (статус FREE) и происходит
     * перегенерация сетки слотов на весь горизонт вперёд. Учтенные бронирования (BOOKED) не затрагиваются.
     */
    @Transactional
    public void activateTemplate(Long templateId, LocalDate from, LocalDate until) {
        ScheduleTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        // Деактивируем текущий активный шаблон
        templateRepository.findActive().ifPresent(current -> {
            current.deactivate();
            templateRepository.save(current);
        });

        template.activate(from, until);
        templateRepository.save(template);

        // Удаляем все сгенерированные FREE-слоты начиная с даты активации
        timeSlotRepository.deleteFreeGeneratedSlotsFrom(from);

        // Перегенерируем слоты на горизонт вперёд
        LocalDate end = LocalDate.now().plusDays(BOOKING_HORIZON_DAYS);
        int generated = generateSlotsForRange(from, end);

        log.info("Шаблон '{}' активирован с {}. Сгенерировано {} слотов.",
                template.getName(), from, generated);
    }

    /**
     * Возвращает все существующие шаблоны расписания, отсортированные по дате создания в обратном порядке.
     */
    @Transactional(readOnly = true)
    public List<ScheduleTemplate> getAllTemplates() {
        return templateRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Возвращает действующий на данный момент активный шаблон расписания.
     */
    @Transactional(readOnly = true)
    public ScheduleTemplate getActiveTemplate() {
        return templateRepository.findActive()
                .orElseThrow(NoActiveTemplateException::new);
    }

    // Генерация слотов

    /**
     * Генерирует слоты времени для конкретного календарного дня.
     * <p>
     * Сначала проверяет наличие переопределения ({@link DayOverride}) на указанную дату.
     * Если переопределения нет, использует конфигурацию активного шаблона для соответствующего дня недели.
     * Уже существующие на этот день и время слоты пропускаются (защита от дубликатов).
     */
    @Transactional
    public int generateSlotsForDay(LocalDate date) {
        ScheduleTemplate template = templateRepository.findActive()
                .orElseThrow(NoActiveTemplateException::new);

        if (!template.isApplicableTo(date)) {
            log.debug("Шаблон не применим к дате {}", date);
            return 0;
        }

        // Если есть переопределение на эту дату — оно имеет приоритет
        Optional<DayOverride> override = overrideRepository.findByDate(date);

        boolean isWorkingDay;
        LocalTime startTime;
        LocalTime endTime;

        if (override.isPresent()) {
            DayOverride ov = override.get();
            isWorkingDay = ov.isWorking();
            startTime = ov.getStartTime();
            endTime = ov.getEndTime();
            log.debug("Дата {} имеет override типа {}", date, ov.getOverrideType());
        } else {
            // Берём конфигурацию из шаблона (день недели 1=Пн..7=Вс)
            int dow = date.getDayOfWeek().getValue();
            TemplateDay dayConfig = template.getDayConfig(dow);
            isWorkingDay = dayConfig.isWorking();
            startTime = dayConfig.getStartTime();
            endTime = dayConfig.getEndTime();
        }

        if (!isWorkingDay || startTime == null || endTime == null) {
            log.debug("Дата {} — выходной, слоты не генерируются", date);
            return 0;
        }

        // Нарезаем день на слоты равной длины
        List<TimeSlot> slots = new ArrayList<>();
        LocalTime cursor = startTime;
        int duration = template.getSlotDurationHours();

        while (!cursor.plusHours(duration).isAfter(endTime)) {
            LocalTime slotEnd = cursor.plusHours(duration);

            // Защита от дублей — не создаём если слот уже есть
            if (!timeSlotRepository.existsByDateAndStartTime(date, cursor)) {
                slots.add(TimeSlot.builder()
                        .date(date)
                        .startTime(cursor)
                        .endTime(slotEnd)
                        .status(SlotStatus.FREE)
                        .manual(false)
                        .build());
            }

            cursor = slotEnd;
        }

        if (!slots.isEmpty()) {
            timeSlotRepository.saveAll(slots);
        }

        log.debug("Дата {}: сгенерировано {} слотов", date, slots.size());
        return slots.size();
    }

    /**
     * Последовательно генерирует слоты для каждого дня внутри указанного периода.
     */
    @Transactional
    public int generateSlotsForRange(LocalDate from, LocalDate to) {
        int total = 0;
        LocalDate current = from;
        while (!current.isAfter(to)) {
            total += generateSlotsForDay(current);
            current = current.plusDays(1);
        }
        return total;
    }

    /**
     * Пополняет слоты на следующий день на границе горизонта.
     * Вызывается фоновым планировщиком (ночным кроном) для поддержания постоянной глубины записи в {@value #BOOKING_HORIZON_DAYS} дней.
     */
    @Transactional
    public void generateNextDay() {
        LocalDate maxDate = timeSlotRepository.findMaxGeneratedDate()
                .orElse(LocalDate.now().minusDays(1));

        LocalDate nextDay = maxDate.plusDays(1);
        LocalDate horizon = LocalDate.now().plusDays(BOOKING_HORIZON_DAYS);

        if (!nextDay.isAfter(horizon)) {
            int count = generateSlotsForDay(nextDay);
            log.info("Планировщик: сгенерировано {} слотов на {}", count, nextDay);
        }
    }

    // Управление слотами

    /**
     * Блокирует выбранный слот времени вручную мастером (например, для личных дел).
     */
    @Transactional
    public void blockSlot(Long slotId, String reason) {
        TimeSlot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));

        if (slot.isBooked()) {
            throw new GroomBookException(
                    "Слот #" + slotId + " уже занят активной бронью. " +
                            "Сначала отмените бронь, затем заблокируйте слот.");
        }

        slot.block(reason);
        timeSlotRepository.save(slot);

        log.info("Слот #{} заблокирован мастером. Причина: {}", slotId, reason);
    }

    /**
     * Снимает ручную блокировку со слота, возвращая ему статус {@link SlotStatus#FREE}.
     */
    @Transactional
    public void unblockSlot(Long slotId) {
        TimeSlot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));

        if (slot.getStatus() != SlotStatus.BLOCKED) {
            throw new GroomBookException("Слот #" + slotId + " не заблокирован");
        }

        slot.markFree();
        timeSlotRepository.save(slot);

        log.info("Блокировка снята со слота #{}", slotId);
    }

    // Управление днями — переопределения

    /**
     * Объявляет отдельный день полностью нерабочим (праздник, выходной).
     * Создает запись переопределения и физически удаляет все свободные (FREE) слоты этого дня.
     */
    @Transactional
    public void blockDay(LocalDate date, OverrideType type, String reason) {
        saveOverride(DayOverride.builder()
                .date(date)
                .overrideType(type)
                .working(false)
                .reason(reason)
                .build());

        timeSlotRepository.deleteFreeSlotsByDate(date);

        log.info("День {} заблокирован. Тип: {}, причина: {}", date, type, reason);
    }

    /**
     * Запрещает работу на целый диапазон дат (например, отпуск мастера).
     * Удаляет не забронированные слоты за весь выбранный период.
     */
    @Transactional
    public void blockDateRange(LocalDate from, LocalDate to,
                               OverrideType type, String reason) {
        LocalDate current = from;
        while (!current.isAfter(to)) {
            // Не перезаписываем если уже есть override на эту дату
            if (!overrideRepository.existsByDate(current)) {
                overrideRepository.save(DayOverride.builder()
                        .date(current)
                        .overrideType(type)
                        .working(false)
                        .reason(reason)
                        .build());
            }
            timeSlotRepository.deleteFreeSlotsByDate(current);
            current = current.plusDays(1);
        }

        log.info("Диапазон {}-{} заблокирован. Тип: {}", from, to, type);
    }

    /**
     * Изменяет стандартные рабочие часы для конкретного дня[cite: 18].
     * Пересоздает незабронированные слоты на указанный день в соответствии с новым графиком.
     */
    @Transactional
    public void overrideDayHours(LocalDate date, LocalTime startTime,
                                 LocalTime endTime, String reason) {
        saveOverride(DayOverride.customHours(date, startTime, endTime, reason));

        // Удаляем старые FREE-слоты и генерируем по новому расписанию
        timeSlotRepository.deleteFreeSlotsByDate(date);
        generateSlotsForDay(date);

        log.info("Часы работы {} изменены: {}-{}. Причина: {}",
                date, startTime, endTime, reason);
    }

    /**
     * Делает базово нерабочий день дополнительно рабочим по согласованию.
     */
    @Transactional
    public void addExtraWorkingDay(LocalDate date, LocalTime startTime,
                                   LocalTime endTime) {
        saveOverride(DayOverride.extraWorkingDay(date, startTime, endTime));
        generateSlotsForDay(date);

        log.info("Добавлен внеплановый рабочий день {}: {}-{}",
                date, startTime, endTime);
    }

    /**
     * Отменяет индивидуальное переопределение дня.
     * Удаляет существующее переопределение и перегенерирует слоты дня в соответствии с общим шаблоном.
     */
    @Transactional
    public void removeOverride(LocalDate date) {
        overrideRepository.findByDate(date).ifPresent(override -> {
            overrideRepository.delete(override);
            // Перегенерируем слоты по шаблону
            timeSlotRepository.deleteFreeSlotsByDate(date);
            generateSlotsForDay(date);
            log.info("Переопределение для {} удалено, слоты перегенерированы", date);
        });
    }

    // Запросы слотов

    /**
     * Возвращает список свободных слотов на выбранный день.
     * Предназначен для показа доступного времени клиентам в Telegram-боте.
     */
    @Transactional(readOnly = true)
    public List<TimeSlot> getAvailableSlots(LocalDate date) {
        return timeSlotRepository.findByDateAndStatusOrderByStartTimeAsc(
                date, SlotStatus.FREE);
    }

    /**
     * Возвращает полную сетку всех слотов за день (включая заблокированные и забронированные).
     * Предназначен для административных экранов мастера.
     */
    @Transactional(readOnly = true)
    public List<TimeSlot> getAllSlotsForMaster(LocalDate date) {
        return timeSlotRepository.findByDateOrderByStartTimeAsc(date);
    }

    /**
     * Возвращает список дат в пределах горизонта записи, на которые есть хотя бы один свободный слот.
     */
    @Transactional(readOnly = true)
    public List<LocalDate> getAvailableDates() {
        LocalDate from = LocalDate.now().plusDays(1);
        LocalDate to = LocalDate.now().plusDays(BOOKING_HORIZON_DAYS);

        return timeSlotRepository.findFreeSlotsBetween(from, to)
                .stream()
                .map(TimeSlot::getDate)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Конструирует новый шаблон расписания из параметров, собранных визардом.
     * По умолчанию создаваемый шаблон создается неактивным (требует явной активации).
     */
    @Transactional
    public ScheduleTemplate createTemplate(String name,
                                           int slotDurationHours,
                                           Set<Integer> workingDays,
                                           LocalTime startTime,
                                           LocalTime endTime) {
        ScheduleTemplate template = ScheduleTemplate.builder()
                .name(name)
                .active(false)       // новый шаблон всегда неактивен — мастер активирует сам
                .slotDurationHours(slotDurationHours)
                .days(new java.util.ArrayList<>())
                .build();

        // Создаём запись для каждого дня недели (1=Пн..7=Вс)
        for (int dow = 1; dow <= 7; dow++) {
            boolean isWorking = workingDays.contains(dow);
            TemplateDay day = TemplateDay.builder()
                    .template(template)
                    .dayOfWeek(dow)
                    .working(isWorking)
                    .startTime(isWorking ? startTime : null)
                    .endTime(isWorking ? endTime : null)
                    .build();
            template.getDays().add(day);
        }

        templateRepository.save(template);
        log.info("Создан шаблон '{}' слот={}ч рабочихДней={}",
                name, slotDurationHours, workingDays.size());
        return template;
    }

    // Приватные вспомогательные методы

    /**
     * Сохраняет или обновляет объект переопределения дня без дублирования.
     */
    private void saveOverride(DayOverride override) {
        // Если уже есть — заменяем, не дублируем
        overrideRepository.findByDate(override.getDate())
                .ifPresent(existing -> {
                    override.setId(existing.getId());
                });
        overrideRepository.save(override);
    }
}
