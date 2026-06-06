package org.example.groombook.exception;

/**
 * Клиент исчерпал лимит броней на текущую неделю (максимум 2).
 * Бот: "На этой неделе вы уже записаны максимальное количество раз."
 */
public class BookingLimitExceededException extends GroomBookException {

    public BookingLimitExceededException(Long clientId) {
        super("Клиент #" + clientId + " превысил лимит броней на неделю");
    }
}
