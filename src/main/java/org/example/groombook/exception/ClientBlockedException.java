package org.example.groombook.exception;

/**
 * Клиент заблокирован мастером — запись недоступна.
 * Бот: "Запись временно недоступна. Свяжитесь с мастером напрямую."
 * ВАЖНО: причина блокировки клиенту никогда не сообщается.
 */
public class ClientBlockedException extends GroomBookException {

    public ClientBlockedException(Long clientId) {
        super("Клиент #" + clientId + " заблокирован");
    }
}
