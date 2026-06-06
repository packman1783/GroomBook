package org.example.groombook.repository;

import org.example.groombook.model.Client;
import org.example.groombook.model.enums.ClientStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByTelegramId(Long telegramId);

    Optional<Client> findByPhone(String phone);

    boolean existsByTelegramId(Long telegramId);

    boolean existsByPhone(String phone);

    /** Клиенты с определённым статусом — для фильтрации в управлении мастера */
    List<Client> findByStatus(ClientStatus status);

    /**
     * Клиенты, у которых не было завершённых визитов дольше N дней.
     * Используется в StatisticsService для отчёта "давно не приходили".
     */
    @Query("""
            SELECT c FROM Client c
            WHERE c.status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT b FROM Booking b
                  WHERE b.client = c
                    AND b.status = 'COMPLETED'
                    AND b.completedAt >= :since
              )
            """)
    List<Client> findInactiveClients(@Param("since") LocalDateTime since);
}
