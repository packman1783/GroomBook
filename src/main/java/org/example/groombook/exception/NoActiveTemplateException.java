package org.example.groombook.exception;

/**
 * Активный шаблон расписания не найден.
 * Возникает если мастер ещё не настроил ни одного шаблона.
 * Бот мастеру: "Сначала создайте и активируйте шаблон расписания (/schedule)."
 */
public class NoActiveTemplateException extends GroomBookException {

    public NoActiveTemplateException() {
        super("Активный шаблон расписания не найден. Настройте расписание.");
    }
}
