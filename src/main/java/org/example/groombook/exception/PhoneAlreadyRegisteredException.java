package org.example.groombook.exception;

/**
 * Номер телефона уже привязан к другому клиенту.
 * Бот: "Этот номер телефона уже зарегистрирован. Если это вы — свяжитесь с мастером."
 */
public class PhoneAlreadyRegisteredException extends GroomBookException {

    public PhoneAlreadyRegisteredException(String phone) {
        super("Номер телефона " + phone + " уже зарегистрирован");
    }
}
