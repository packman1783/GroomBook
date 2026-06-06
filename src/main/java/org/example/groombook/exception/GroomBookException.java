package org.example.groombook.exception;

/**
 * Базовое доменное исключение.
 * Все бизнес-ошибки наследуются от него —
 * это позволяет перехватывать их в одном месте в боте.
 */
public class GroomBookException extends RuntimeException {

    public GroomBookException(String message) {
        super(message);
    }

    public GroomBookException(String message, Throwable cause) {
        super(message, cause);
    }
}
