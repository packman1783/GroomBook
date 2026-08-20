package org.example.groombook.bot.keyboard;

import org.example.groombook.bot.util.CallbackData;
import org.example.groombook.model.Pet;
import org.example.groombook.model.TimeSlot;

import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

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
        
        rows.add(new InlineKeyboardRow(button("❌ Отмена", CallbackData.BOOK_CANCEL)));
        
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Клавиатура выбора слота на дату — по 2 слота в ряд для компактности
     */
    public InlineKeyboardMarkup slotsKeyboard(List<TimeSlot> slots) {
        var builder = InlineKeyboardMarkup.builder();
        
        for (int i = 0; i < slots.size(); i += 2) {
            var btn1 = button(formatSlotLabel(slots.get(i)),
                    CallbackData.build(CallbackData.BOOK_SLOT, slots.get(i).getId()));
            
            if (i + 1 < slots.size()) {
                var btn2 = button(formatSlotLabel(slots.get(i+1)),
                        CallbackData.build(CallbackData.BOOK_SLOT, slots.get(i+1).getId()));
                builder.keyboardRow(new InlineKeyboardRow(btn1, btn2));
            } else {
                builder.keyboardRow(new InlineKeyboardRow(btn1));
            }
        }
        
        builder.keyboardRow(new InlineKeyboardRow(button("❌ Отмена", CallbackData.BOOK_CANCEL)));
        
        return builder.build();
    }

    private String formatSlotLabel(TimeSlot slot) {
        return slot.getStartTime().format(TIME_FMT) + "–" + slot.getEndTime().format(TIME_FMT);
    }

    /**
     * Клавиатура выбора питомца — с эмодзи по типу
     */
    public InlineKeyboardMarkup petsKeyboard(List<Pet> pets) {
        var rows = pets.stream()
                .map(pet -> new InlineKeyboardRow(button(petEmoji(pet) + " " + pet.getName(),
                        CallbackData.build(CallbackData.BOOK_PET, pet.getId()))))
                .collect(Collectors.toList());
        
        rows.add(new InlineKeyboardRow(button("❌ Отмена", CallbackData.BOOK_CANCEL)));
        
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
     * Кнопка для пропуска ввода комментария
     */
    public InlineKeyboardMarkup skipCommentKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button("➡️ Продолжить без комментария", CallbackData.SKIP_COMMENT)))
                .keyboardRow(new InlineKeyboardRow(button("❌ Отмена", CallbackData.BOOK_CANCEL)))
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

    /**
     * Главное меню Мастера (ReplyKeyboard)
     */
    public ReplyKeyboardMarkup masterMainMenu() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow("📅 Сегодня", "📅 Завтра"))
                .keyboardRow(new KeyboardRow("🗓 Неделя", "🗓 2 недели"))
                .keyboardRow(new KeyboardRow("⚙️ Расписание", "🆕 Шаблон"))
                .keyboardRow(new KeyboardRow("📝 Записать вручную", "🚫 Блок-лист"))
                .keyboardRow(new KeyboardRow("❓ Помощь"))
                .resizeKeyboard(true)
                .build();
    }

    /**
     * Главное меню Клиента (ReplyKeyboard)
     */
    public ReplyKeyboardMarkup clientMainMenu() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow("📅 Записаться"))
                .keyboardRow(new KeyboardRow("🐾 Мои питомцы", "📋 Мои записи"))
                .keyboardRow(new KeyboardRow("❓ Помощь"))
                .resizeKeyboard(true)
                .build();
    }

    /**
     * Клавиатура для запроса номера телефона (ReplyKeyboard)
     */
    public ReplyKeyboardMarkup contactKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(
                        KeyboardButton.builder()
                                .text("📱 Отправить мой номер телефона")
                                .requestContact(true)
                                .build()
                ))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
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
