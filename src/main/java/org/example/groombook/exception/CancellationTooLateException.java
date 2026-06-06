package org.example.groombook.exception;


/**
 * Попытка отменить бронь позже чем за 24 часа до начала.
 * Бот: "Отменить запись можно не позднее чем за 24 часа до начала."
 */
public class CancellationTooLateException extends GroomBookException {

    public CancellationTooLateException(Long bookingId) {
        super("Бронь #" + bookingId + " нельзя отменить — до начала менее 24 часов");
    }
}
