package org.example.groombook.bot.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище сессий в памяти, ключ — Telegram ID пользователя.
 * <p>
 * ВАЖНО: Для одного мастера и небольшого потока клиентов in-memory хранилище
 * полностью достаточно. При перезапуске приложения сессии сбрасываются.
 */
@Component
public class SessionManager {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public UserSession get(Long telegramId) {
        return sessions.computeIfAbsent(telegramId, id -> new UserSession());
    }

    public void clear(Long telegramId) {
        UserSession session = sessions.get(telegramId);
        if (session != null) {
            session.reset();
        }
    }

    public void remove(Long telegramId) {
        sessions.remove(telegramId);
    }
}
