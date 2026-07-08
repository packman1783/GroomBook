package org.example.groombook.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * TelegramClient как отдельный Spring-бин.
 * Используется и в GroomBookBot (для consume/ответов), и в TelegramNotificationService
 * (для уведомлений мастеру и клиенту) — единая точка создания клиента API.
 */
@Configuration
public class TelegramBotConfig {

    @Value("${grooming.bot.token}")
    private String botToken;

    @Bean
    public TelegramClient telegramClient() {
        return new OkHttpTelegramClient(botToken);
    }
}
