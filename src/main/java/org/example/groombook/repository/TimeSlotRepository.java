package org.example.groombook.repository;

import org.example.groombook.model.TimeSlot;
import org.example.groombook.model.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {

    /** Все слоты на конкретную дату — для мастера (видит блокировки и причины) */
    List<TimeSlot> findByDateOrderByStartTimeAsc(LocalDate date);

    /** Свободные слоты на дату — для клиента при бронировании */
    List<TimeSlot> findByDateAndStatusOrderByStartTimeAsc(LocalDate date, SlotStatus status);

    /**
     * Свободные слоты на диапазон дат — для показа доступных дней.
     * Используется при отображении календаря клиенту.
     */
    @Query("""
            SELECT s FROM TimeSlot s
            WHERE s.date BETWEEN :from AND :to
              AND s.status = 'FREE'
            ORDER BY s.date, s.startTime
            """)
    List<TimeSlot> findFreeSlotsBetween(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Проверка: существует ли слот на конкретную дату и время.
     * Защита от дублирования при генерации.
     */
    boolean existsByDateAndStartTime(LocalDate date, LocalTime startTime);

    /**
     * Найти слот по дате и времени начала.
     * Используется при создании брони по выбранному времени.
     */
    Optional<TimeSlot> findByDateAndStartTime(LocalDate date, LocalTime startTime);

    /**
     * Удалить все FREE-слоты начиная с указанной даты.
     * Вызывается при переключении шаблона расписания —
     * BOOKED и BLOCKED слоты не затрагиваются.
     */
    @Modifying
    @Query("""
            DELETE FROM TimeSlot s
            WHERE s.date >= :from
              AND s.status = 'FREE'
              AND s.manual = false
            """)
    void deleteFreeGeneratedSlotsFrom(@Param("from") LocalDate from);

    /**
     * Удалить FREE-слоты на конкретный день.
     * Вызывается при блокировке дня (праздник, отпуск).
     */
    @Modifying
    @Query("""
            DELETE FROM TimeSlot s
            WHERE s.date = :date
              AND s.status = 'FREE'
            """)
    void deleteFreeSlotsByDate(@Param("date") LocalDate date);

    /**
     * Проверка: есть ли хотя бы один свободный слот на дату.
     * Оптимизация — не тянуть весь список только ради кнопки в боте.
     */
    /** Все слоты в диапазоне дат */
    List<TimeSlot> findByDateBetween(LocalDate from, LocalDate to);

    boolean existsByDateAndStatus(LocalDate date, SlotStatus status);

    /**
     * Самая поздняя дата среди сгенерированных слотов.
     * Нужна планировщику чтобы понять, до какой даты уже есть слоты.
     */
    @Query("SELECT MAX(s.date) FROM TimeSlot s WHERE s.manual = false")
    Optional<LocalDate> findMaxGeneratedDate();
}
