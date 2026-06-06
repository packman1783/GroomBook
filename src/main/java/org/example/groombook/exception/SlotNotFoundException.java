package org.example.groombook.exception;

/**
 * Слот не найден в базе данных.
 * Возникает если клиент передал несуществующий slotId (например, устаревшая кнопка в боте).
 */
public class SlotNotFoundException extends GroomBookException {

    public SlotNotFoundException(Long slotId) {
        super("Слот #" + slotId + " не найден");
    }
}
