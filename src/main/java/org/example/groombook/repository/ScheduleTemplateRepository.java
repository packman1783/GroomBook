package org.example.groombook.repository;

import org.example.groombook.model.ScheduleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScheduleTemplateRepository extends JpaRepository<ScheduleTemplate, Long> {

    /**
     * Активный шаблон — всегда один.
     * Уникальность гарантируется partial index в БД и логикой ScheduleService.
     */
    @Query("SELECT t FROM ScheduleTemplate t JOIN FETCH t.days WHERE t.active = true")
    Optional<ScheduleTemplate> findActive();

    /** Все шаблоны для отображения в меню управления расписанием */
    List<ScheduleTemplate> findAllByOrderByCreatedAtDesc();
}
