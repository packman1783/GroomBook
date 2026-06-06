package org.example.groombook.repository;

import org.example.groombook.model.DayOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DayOverrideRepository extends JpaRepository<DayOverride, Long> {

    /** Переопределение на конкретную дату */
    Optional<DayOverride> findByDate(LocalDate date);

    /** Переопределения на диапазон дат */
    List<DayOverride> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    /** Проверка: есть ли уже переопределение на эту дату */
    boolean existsByDate(LocalDate date);
}
