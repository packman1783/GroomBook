package org.example.groombook.infrastructure.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleCalendarConfig {

    /**
     * Путь к файлу credentials.json от Google Service Account.
     * Задаётся в application.yml: grooming.google.credentials-path
     *
     * Как получить:
     * 1. Google Cloud Console → IAM → Service Accounts → Create
     * 2. Скачать JSON-ключ
     * 3. В Google Calendar → настройки календаря → поделиться с email сервис-аккаунта
     * 4. Указать путь к JSON в application.yml
     */
    @Value("${grooming.google.credentials-path}")
    private String credentialsPath;

    @Value("${grooming.google.application-name:GroomBook}")
    private String applicationName;

    @Bean
    public Calendar googleCalendarClient() throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(credentialsPath))
                .createScoped(List.of(CalendarScopes.CALENDAR));

        return new Calendar.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(applicationName)
                .build();
    }
}
