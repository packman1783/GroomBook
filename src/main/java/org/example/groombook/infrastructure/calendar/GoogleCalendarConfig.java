package org.example.groombook.infrastructure.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
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

    @Value("${grooming.google.credentials-path}")
    private String credentialsPath;

    @Value("${grooming.google.application-name:GroomBook}")
    private String applicationName;

    @Value("${grooming.google.connect-timeout-ms:5000}")
    private int connectTimeout;

    @Value("${grooming.google.read-timeout-ms:10000}")
    private int readTimeout;

    @Bean
    public Calendar googleCalendarClient() throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(credentialsPath))
                .createScoped(List.of(CalendarScopes.CALENDAR));

        HttpRequestInitializer requestInitializer = request -> {
            new HttpCredentialsAdapter(credentials).initialize(request);
            request.setConnectTimeout(connectTimeout);
            request.setReadTimeout(readTimeout);
        };

        return new Calendar.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                requestInitializer)
                .setApplicationName(applicationName)
                .build();
    }
}
