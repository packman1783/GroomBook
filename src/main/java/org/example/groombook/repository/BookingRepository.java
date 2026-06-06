package org.example.groombook.repository;

import org.example.groombook.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Активная бронь на слот — для проверки при бронировании */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.slot.id = :slotId
              AND b.status IN ('PENDING', 'CONFIRMED')
            """)
    Optional<Booking> findActiveBySlotId(@Param("slotId") Long slotId);

    /** Все активные брони клиента — для команды /mybookings */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.slot s
            JOIN FETCH b.pet p
            WHERE b.client.id = :clientId
              AND b.status IN ('PENDING', 'CONFIRMED')
            ORDER BY s.date, s.startTime
            """)
    List<Booking> findActiveByClientId(@Param("clientId") Long clientId);

    /** Все брони на конкретный день — для команды мастера /today и /tomorrow */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.pet p
            JOIN FETCH b.slot s
            WHERE s.date = :date
              AND b.status IN ('PENDING', 'CONFIRMED')
            ORDER BY s.startTime
            """)
    List<Booking> findByDate(@Param("date") LocalDate date);

    /**
     * Количество активных броней клиента за неделю.
     * Используется для проверки лимита (не более 2 в неделю).
     */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
              AND b.status IN ('PENDING', 'CONFIRMED')
              AND b.createdAt BETWEEN :weekStart AND :weekEnd
            """)
    long countActiveByClientInWeek(
            @Param("clientId") Long clientId,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd
    );

    /** Все брони клиента — для статистики по клиенту */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.slot s
            JOIN FETCH b.pet p
            WHERE b.client.id = :clientId
            ORDER BY s.date DESC, s.startTime DESC
            """)
    List<Booking> findAllByClientId(@Param("clientId") Long clientId);

    /**
     * Подтверждённые брони на завтра — для отправки напоминаний клиентам.
     * Вызывается планировщиком каждый день в 10:00.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.slot s
            WHERE s.date = :tomorrow
              AND b.status = 'CONFIRMED'
            """)
    List<Booking> findConfirmedForDate(@Param("tomorrow") LocalDate tomorrow);

    /**
     * Брони попадающие в диапазон дат.
     * Используется при блокировке отпуска — мастер должен знать,
     * какие записи оказались в заблокированном периоде.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.client c
            JOIN FETCH b.pet p
            JOIN FETCH b.slot s
            WHERE s.date BETWEEN :from AND :to
              AND b.status IN ('PENDING', 'CONFIRMED')
            ORDER BY s.date, s.startTime
            """)
    List<Booking> findActiveInDateRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Статистика за месяц — количество броней по статусам.
     * Используется в StatisticsService для месячного отчёта.
     */
    @Query("""
            SELECT b.status, COUNT(b) FROM Booking b
            JOIN b.slot s
            WHERE s.date BETWEEN :from AND :to
            GROUP BY b.status
            """)
    List<Object[]> countByStatusInPeriod(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Количество уникальных клиентов за период — для статистики */
    @Query("""
            SELECT COUNT(DISTINCT b.client.id) FROM Booking b
            JOIN b.slot s
            WHERE s.date BETWEEN :from AND :to
              AND b.status = 'COMPLETED'
            """)
    long countUniqueClientsInPeriod(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
