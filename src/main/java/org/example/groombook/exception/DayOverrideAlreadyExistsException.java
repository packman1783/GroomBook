package org.example.groombook.exception;

import java.time.LocalDate;

/**
 * На указанную дату уже существует переопределение расписания.
 * Мастер должен сначала удалить существующее, прежде чем создавать новое.
 */
public class DayOverrideAlreadyExistsException extends GroomBookException {

    public DayOverrideAlreadyExistsException(LocalDate date) {
        super("На дату " + date + " уже существует переопределение расписания");
    }
}
