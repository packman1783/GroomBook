package org.example.groombook.exception;

/**
 * Бронь не найдена в базе данных.
 */
public class BookingNotFoundException extends GroomBookException {

    public BookingNotFoundException(Long bookingId) {
        super("Бронь #" + bookingId + " не найдена");
    }
}
