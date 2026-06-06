package org.example.groombook.exception;

/**
 * Попытка совершить действие с бронью в недопустимом статусе.
 * Пример: подтвердить уже отменённую бронь, или закрыть уже завершённую.
 */
public class InvalidBookingStatusException extends GroomBookException {

    public InvalidBookingStatusException(Long bookingId, String action, String currentStatus) {
        super("Невозможно выполнить '" + action + "' для брони #"
                + bookingId + " со статусом " + currentStatus);
    }
}
