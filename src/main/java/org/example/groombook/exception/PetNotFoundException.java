package org.example.groombook.exception;

/**
 * Питомец не найден или не принадлежит данному клиенту.
 */
public class PetNotFoundException extends GroomBookException {

    public PetNotFoundException(Long petId) {
        super("Питомец #" + petId + " не найден");
    }
}
