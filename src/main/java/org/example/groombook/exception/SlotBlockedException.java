package org.example.groombook.exception;

/**
 * Слот заблокирован мастером.
 * Бот показывает слот как "занято" — причина клиенту не раскрывается.
 */
public class SlotBlockedException extends GroomBookException {

    public SlotBlockedException(Long slotId) {
        super("Слот #" + slotId + " заблокирован мастером");
    }
}
