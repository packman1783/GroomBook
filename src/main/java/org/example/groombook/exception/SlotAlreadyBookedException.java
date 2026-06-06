package org.example.groombook.exception;

/**
 * Слот уже занят другим клиентом в момент бронирования.
 * Бот: "К сожалению, этот слот только что забронировали. Выберите другое время."
 */
public class SlotAlreadyBookedException extends GroomBookException {

    public SlotAlreadyBookedException(Long slotId) {
        super("Слот #" + slotId + " уже забронирован");
    }
}
