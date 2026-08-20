package org.example.groombook.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.bot.handler.ClientHandler;
import org.example.groombook.bot.handler.MasterHandler;
import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Точка маршрутизации всех входящих апдейтов.
 *
 * Логика разделения ролей простая: один Telegram ID мастера задан в конфиге.
 * Все остальные пользователи — клиенты. Это сознательное упрощение для
 * сервиса с одним мастером — не нужна полноценная система ролей.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateDispatcher {

    private final ClientHandler   clientHandler;
    private final MasterHandler   masterHandler;
    private final SessionManager  sessionManager;

    @Value("${grooming.master.telegram-id}")
    private Long masterTelegramId;

    public void dispatch(Update update) {
        if (update.hasCallbackQuery()) {
            dispatchCallback(update.getCallbackQuery());
        } else if (update.hasMessage()) {
            dispatchMessage(update.getMessage());
        }
        // Прочие типы апдейтов (фото, стикеры и т.д.) сейчас не обрабатываются
    }

    private void dispatchMessage(Message message) {
        Long telegramId = message.getChatId();
        boolean isMaster = telegramId.equals(masterTelegramId);
        SessionState state = sessionManager.get(telegramId).getState();

        if (message.hasText()) {
            String text = message.getText().trim();

            // Команды (начинаются с "/") или текст с кнопок главного меню всегда сбрасывают текущий 
            // многошаговый сценарий — пользователь явно переключился на другое действие
            if (text.startsWith("/") || isMainMenuButton(text)) {
                sessionManager.clear(telegramId);
                if (isMaster) {
                    masterHandler.handleCommand(telegramId, text);
                } else {
                    clientHandler.handleCommand(telegramId, text);
                }
                return;
            }

            // Не команда — значит это ответ в рамках текущего многошагового сценария
            if (state == SessionState.NONE) {
                // Пользователь написал текст без активного сценария — подсказываем команды
                if (isMaster) {
                    masterHandler.handleUnrecognizedText(telegramId);
                } else {
                    clientHandler.handleUnrecognizedText(telegramId);
                }
                return;
            }

            if (isMaster) {
                masterHandler.handleTextInput(telegramId, text, state);
            } else {
                clientHandler.handleTextInput(telegramId, text, state);
            }
        } else if (message.hasContact()) {
            if (!isMaster) {
                clientHandler.handleContact(telegramId, message.getContact().getPhoneNumber(), state);
            }
        }
    }

    private void dispatchCallback(CallbackQuery callbackQuery) {
        Long telegramId = callbackQuery.getFrom().getId();
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();

        boolean isMaster = telegramId.equals(masterTelegramId);

        if (isMaster) {
            masterHandler.handleCallback(telegramId, data, callbackId);
        } else {
            clientHandler.handleCallback(telegramId, data, callbackId);
        }
    }

    private boolean isMainMenuButton(String text) {
        return switch (text) {
            case "📅 Сегодня", "📅 Завтра", "🗓 Неделя", "🗓 2 недели", "⚙️ Расписание",
                 "🆕 Шаблон", "📝 Записать вручную", "🚫 Блок-лист", "❓ Помощь",
                 "📅 Записаться", "🐾 Мои питомцы", "📋 Мои записи" -> true;
            default -> false;
        };
    }
}
