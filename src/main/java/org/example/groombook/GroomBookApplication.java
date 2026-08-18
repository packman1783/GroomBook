package org.example.groombook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@EnableScheduling
public class GroomBookApplication {

    public static void main(String[] args) {
        // systemProperties() делает переменные из .env видимыми для @Value("${...}")
        Dotenv.configure().systemProperties().load();

        SpringApplication.run(GroomBookApplication.class, args);
    }

}
