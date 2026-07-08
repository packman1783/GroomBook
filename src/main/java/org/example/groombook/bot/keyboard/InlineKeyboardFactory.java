package org.example.groombook.bot.keyboard;

import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Построение инлайн-клавиатур для клиентских сценариев бронирования.
 * Каждый ряд — отдельная дата/слот/питомец, чтобы было удобно листать на телефоне.
 */
@Component
public class InlineKeyboardFactory {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Клавиатура выбора даты — одна дата на ряд, формат "14 мая (Ср)"
     */
    public InlineKeyboardMarkup datesKeyboard(List<LocalDate> dates) {
        var rows = dates.stream()
                .map(date -> new InlineKeyboardRow(button(formatDateLabel(date),
                        CallbackData.build(CallbackData.BOOK_DATE, date))))
                .collect(Collectors.toList());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Клавиатура выбора слота на дату — формат "11:00–13:00"
     */
    public InlineKeyboardMarkup slotsKeyboard(List<TimeSlot> slots) {
        var rows = slots.stream()
                .map(slot -> new InlineKeyboardRow(button(
                        slot.getStartTime().format(TIME_FMT) + "–" + slot.getEndTime().format(TIME_FMT),
                        CallbackData.build(CallbackData.BOOK_SLOT, slot.getId()))))
                .collect(Collectors.toList());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Клавиатура выбора питомца — с эмодзи по типу
     */
    public InlineKeyboardMarkup petsKeyboard(List<Pet> pets) {
        var rows = pets.stream()
                .map(pet -> new InlineKeyboardRow(button(petEmoji(pet) + " " + pet.getName(),
                        CallbackData.build(CallbackData.BOOK_PET, pet.getId()))))
                .collect(Collectors.toList());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Клавиатура выбора типа питомца — для команды /addpet
     */
    public InlineKeyboardMarkup petTypeKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        button("🐕 Собака", CallbackData.build(CallbackData.PET_TYPE, "DOG")),
                        button("🐈 Кошка", CallbackData.build(CallbackData.PET_TYPE, "CAT")),
                        button("🐾 Другое", CallbackData.build(CallbackData.PET_TYPE, "OTHER"))
                ))
                .build();
    }

    /**
     * Кнопка "Отменить" для одной брони в списке /mybookings
     */
    public InlineKeyboardMarkup cancelBookingKeyboard(Long bookingId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button("❌ Отменить запись",
                        CallbackData.build(CallbackData.CANCEL_BOOKING, bookingId))))
                .build();
    }

    /**
     * Подтверждение отмены — "Да, отменить" / "Нет"
     */
    public InlineKeyboardMarkup confirmCancelKeyboard(Long bookingId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        button("✅ Да, отменить", CallbackData.build(CallbackData.CANCEL_CONFIRM, bookingId)),
                        button("↩️ Нет", CallbackData.CANCEL_ABORT)
                ))
                .build();
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private String formatDateLabel(LocalDate date) {
        String dayName = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, new Locale("ru"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"));
        return date.format(fmt) + " (" + capitalize(dayName) + ")";
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String petEmoji(Pet pet) {
        return switch (pet.getType()) {
            case DOG -> "🐕";
            case CAT -> "🐈";
            case OTHER -> "🐾";
        };
    }
}
