package org.example.groombook.exception;

import java.time.LocalDate;

/**
 * Клиент исчерпал лимит броней на текущую неделю (максимум 2).
 * Бот: "На этой неделе вы уже записаны максимальное количество раз."
 */
public class BookingLimitExceededException extends GroomBookException {

    private final LocalDate nextAvailableDate;

    public BookingLimitExceededException(Long clientId, LocalDate nextAvailableDate) {
        super("Клиент #" + clientId + " превысил лимит броней на неделю. Следующая запись возможна с " + nextAvailableDate);
        this.nextAvailableDate = nextAvailableDate;
    }

    public LocalDate getNextAvailableDate() {
        return nextAvailableDate;
    }
}
