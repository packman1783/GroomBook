package org.example.groombook.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CallbackDataTest {

    @Test
    void prefix_shouldReturnPartBeforeColon() {
        assertEquals("BOOK_SLOT", CallbackData.prefix("BOOK_SLOT:42"));
        assertEquals("CANCEL", CallbackData.prefix("CANCEL:123:abc"));
    }

    @Test
    void prefix_shouldReturnOriginalStringIfNoColon() {
        assertEquals("BOOK_DATE", CallbackData.prefix("BOOK_DATE"));
    }

    @Test
    void payload_shouldReturnPartAfterColon() {
        assertEquals("42", CallbackData.payload("BOOK_SLOT:42"));
        assertEquals("123:abc", CallbackData.payload("CANCEL:123:abc"));
    }

    @Test
    void payload_shouldReturnEmptyStringIfNoColon() {
        assertEquals("", CallbackData.payload("BOOK_DATE"));
    }

    @Test
    void payloadAsLong_shouldParseValue() {
        assertEquals(42L, CallbackData.payloadAsLong("BOOK_SLOT:42"));
    }

    @Test
    void payloadAsLong_shouldThrowExceptionForInvalidFormat() {
        assertThrows(NumberFormatException.class, () -> CallbackData.payloadAsLong("BOOK_SLOT:abc"));
        assertThrows(NumberFormatException.class, () -> CallbackData.payloadAsLong("BOOK_DATE"));
    }

    @Test
    void build_shouldCreateFormattedString() {
        assertEquals("BOOK_SLOT:42", CallbackData.build(CallbackData.BOOK_SLOT, 42));
        assertEquals("BOOK_DATE:2023-10-27", CallbackData.build(CallbackData.BOOK_DATE, "2023-10-27"));
    }
}
