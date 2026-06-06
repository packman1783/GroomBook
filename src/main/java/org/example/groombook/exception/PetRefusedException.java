package org.example.groombook.exception;

/**
 * Питомец помечен мастером как REFUSED — работа с ним прекращена.
 * Бот: "К сожалению, запись для этого питомца недоступна."
 * Причина клиенту не раскрывается.
 */
public class PetRefusedException extends GroomBookException {

    public PetRefusedException(Long petId) {
        super("Питомец #" + petId + " помечен как REFUSED — запись недоступна");
    }
}
