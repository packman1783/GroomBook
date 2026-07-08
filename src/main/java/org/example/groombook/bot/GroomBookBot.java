package org.example.groombook.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Главный класс бота.
 *
 * В telegrambots 10.x бот не наследует AbsSender — вместо этого
 * реализует два интерфейса:
 *   SpringLongPollingBot              — для авто-регистрации стартером Spring Boot
 *   LongPollingSingleThreadUpdateConsumer — для получения апдейтов через consume()
 *
 * Вся реальная логика обработки живёт в UpdateDispatcher —
 * этот класс — только точка входа, ничего не решает сам.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroomBookBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final UpdateDispatcher updateDispatcher;

    @Value("${grooming.bot.token}")
    private String botToken;

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            updateDispatcher.dispatch(update);
        } catch (Exception e) {
            // Любая необработанная ошибка не должна "убивать" поток обработки апдейтов
            log.error("Необработанная ошибка при обработке апдейта: {}", update, e);
        }
    }
}
