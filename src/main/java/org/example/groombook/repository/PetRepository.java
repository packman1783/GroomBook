package org.example.groombook.repository;

import org.example.groombook.model.Pet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PetRepository extends JpaRepository<Pet, Long> {

    /** Активные питомцы клиента — для показа списка при бронировании */
    List<Pet> findByClientIdAndActiveTrue(Long clientId);

    /** Все питомцы клиента включая деактивированных — для истории */
    List<Pet> findByClientId(Long clientId);
}
