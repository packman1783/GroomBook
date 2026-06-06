package org.example.groombook.exception;

/**
 * Попытка забронировать слот менее чем за 1 час до его начала.
 * Бот: "Запись возможна не позднее чем за 1 час до начала."
 */
public class SlotTooSoonException extends GroomBookException {

    public SlotTooSoonException() {
        super("Запись возможна не позднее чем за 1 час до начала слота");
    }
}
