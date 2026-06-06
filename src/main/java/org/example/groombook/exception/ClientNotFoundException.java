package org.example.groombook.exception;

/**
 * Клиент не найден по Telegram ID.
 * Возникает при обращении незарегистрированного пользователя.
 */
public class ClientNotFoundException extends GroomBookException {

    public ClientNotFoundException(Long telegramId) {
        super("Клиент с Telegram ID " + telegramId + " не найден");
    }
}
