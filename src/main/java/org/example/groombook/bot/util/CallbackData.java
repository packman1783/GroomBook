package org.example.groombook.bot.util;

/**
 * Префиксы callback_data для инлайн-кнопок и утилиты их разбора.
 * Формат всегда: "PREFIX:значение", например "BOOK_SLOT:42".
 * <p>
 * Единое место для всех префиксов — чтобы не плодить магические строки
 * по ClientHandler / MasterHandler / NotificationService.
 */
public final class CallbackData {

    private CallbackData() {
    }

    // ── Клиентские сценарии ───────────────────────────────────────────────────
    public static final String BOOK_DATE = "BOOK_DATE";
    public static final String BOOK_SLOT = "BOOK_SLOT";
    public static final String BOOK_PET = "BOOK_PET";
    public static final String PET_TYPE = "PET_TYPE";
    public static final String CANCEL_BOOKING = "CANCEL_BOOKING";
    public static final String CANCEL_CONFIRM = "CANCEL_CONFIRM";
    public static final String CANCEL_ABORT = "CANCEL_ABORT";

    // ── Сценарии мастера ───────────────────────────────────────────────────────
    public static final String CONFIRM_BOOKING = "CONFIRM_BOOKING";
    public static final String REJECT_BOOKING = "REJECT_BOOKING";
    public static final String COMPLETE_BOOKING = "COMPLETE_BOOKING";
    public static final String NO_SHOW_BOOKING = "NO_SHOW_BOOKING";
    public static final String BLOCK_SLOT = "BLOCK_SLOT";
    public static final String UNBLOCK_SLOT = "UNBLOCK_SLOT";

    // ── Меню /schedule ──────────────────────────────────────────────────────────
    public static final String SCHEDULE_TEMPLATES = "SCHEDULE_TEMPLATES";
    public static final String SCHEDULE_BLOCK = "SCHEDULE_BLOCK";
    public static final String SCHEDULE_VACATION = "SCHEDULE_VACATION";
    public static final String SCHEDULE_MANUAL = "SCHEDULE_MANUAL";

    // ── Шаблоны ──────────────────────────────────────────────────────────────────
    public static final String TEMPLATE_ACTIVATE = "TEMPLATE_ACTIVATE";

    // ── Блокировка слота: дата → конкретный слот ───────────────────────────────
    public static final String BLOCK_PICK_DATE = "BLOCK_PICK_DATE";
    public static final String BLOCK_PICK_SLOT = "BLOCK_PICK_SLOT";

    // ── Договорная запись: выбор клиента и питомца ──────────────────────────────
    public static final String MANUAL_PICK_CLIENT = "MANUAL_PICK_CLIENT";
    public static final String MANUAL_PICK_PET = "MANUAL_PICK_PET";

    // ── Wizard создания шаблона ──────────────────────────────────────────────────
    public static final String TEMPLATE_NEW           = "TEMPLATE_NEW";
    public static final String TEMPLATE_TOGGLE_DAY    = "TEMPLATE_TOGGLE_DAY";
    public static final String TEMPLATE_DAYS_DONE     = "TEMPLATE_DAYS_DONE";
    public static final String TEMPLATE_START_TIME    = "TEMPLATE_START_TIME";
    public static final String TEMPLATE_END_TIME      = "TEMPLATE_END_TIME";
    public static final String TEMPLATE_DURATION      = "TEMPLATE_DURATION";
    public static final String TEMPLATE_CONFIRM       = "TEMPLATE_CONFIRM";
    public static final String TEMPLATE_CANCEL_WIZARD = "TEMPLATE_CANCEL_WIZARD";

    /**
     * Часть до первого ':' — тип callback
     */
    public static String prefix(String callbackData) {
        int idx = callbackData.indexOf(':');
        return idx == -1 ? callbackData : callbackData.substring(0, idx);
    }

    /**
     * Часть после первого ':' — значение (обычно ID)
     */
    public static String payload(String callbackData) {
        int idx = callbackData.indexOf(':');
        return idx == -1 ? "" : callbackData.substring(idx + 1);
    }

    public static Long payloadAsLong(String callbackData) {
        return Long.parseLong(payload(callbackData));
    }

    public static String build(String prefix, Object value) {
        return prefix + ":" + value;
    }
}
