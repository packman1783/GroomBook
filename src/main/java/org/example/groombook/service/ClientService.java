package org.example.groombook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.example.groombook.exception.ClientNotFoundException;
import org.example.groombook.exception.PetNotFoundException;
import org.example.groombook.exception.PhoneAlreadyRegisteredException;
import org.example.groombook.model.Client;
import org.example.groombook.model.Pet;
import org.example.groombook.model.enums.ClientStatus;
import org.example.groombook.model.enums.PetDifficulty;
import org.example.groombook.model.enums.PetType;
import org.example.groombook.repository.ClientRepository;
import org.example.groombook.repository.PetRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final PetRepository petRepository;

    // Регистрация и поиск клиента

    /**
     * Найти клиента по Telegram ID или создать нового.
     * Вызывается при каждом обращении к боту — определяет новый клиент или нет.
     */
    @Transactional
    public Client getOrCreateClient(Long telegramId, String name, String phone) {
        return clientRepository.findByTelegramId(telegramId)
                .orElseGet(() -> registerNewClient(telegramId, name, phone));
    }

    /**
     * Проверить зарегистрирован ли клиент по Telegram ID.
     * Используется в боте при /start — чтобы не спрашивать данные повторно.
     */
    @Transactional(readOnly = true)
    public boolean isRegistered(Long telegramId) {
        return clientRepository.existsByTelegramId(telegramId);
    }

    /**
     * Получить клиента по Telegram ID.
     * Выбрасывает исключение если клиент не найден.
     */
    @Transactional(readOnly = true)
    public Client getByTelegramId(Long telegramId) {
        return clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));
    }

    /**
     * Получить клиента по ID — для команд мастера.
     */
    @Transactional(readOnly = true)
    public Client getById(Long clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    /**
     * Все клиенты — для поиска при создании договорной записи мастером.
     */
    @Transactional(readOnly = true)
    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    // Управление статусом клиента — только мастер, только вручную

    /**
     * Изменить статус клиента.
     * Блокировка, установка флага, требование подтверждения — всё здесь.
     * Причина хранится в БД и видна только мастеру.
     */
    @Transactional
    public Client changeClientStatus(Long clientId, ClientStatus newStatus,
                                     String reason) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        ClientStatus oldStatus = client.getStatus();
        client.changeStatus(newStatus, reason);
        clientRepository.save(client);

        log.info("Клиент #{} статус изменён: {} → {}. Причина: {}",
                clientId, oldStatus, newStatus, reason);

        return client;
    }

    /**
     * Клиенты не приходившие более N дней — для отчёта мастера "давно не приходили".
     */
    @Transactional(readOnly = true)
    public List<Client> getInactiveClients(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return clientRepository.findInactiveClients(since);
    }

    // Управление питомцами

    /**
     * Добавить питомца клиенту.
     * Вызывается при первой регистрации или командой /addpet.
     */
    @Transactional
    public Pet addPet(Long telegramId, String name, PetType type, String breed) {
        Client client = clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));

        Pet pet = Pet.builder()
                .client(client)
                .name(name)
                .type(type)
                .breed(breed)
                .difficulty(PetDifficulty.EASY)
                .active(true)
                .build();

        petRepository.save(pet);

        log.info("Добавлен питомец '{}' клиенту #{}", name, client.getId());

        return pet;
    }

    /**
     * Активные питомцы клиента — для показа списка при бронировании.
     */
    @Transactional(readOnly = true)
    public List<Pet> getActivePets(Long telegramId) {
        Client client = clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));
        return petRepository.findByClientIdAndActiveTrue(client.getId());
    }

    /**
     * Все питомцы клиента включая деактивированных — для истории и статистики.
     */
    @Transactional(readOnly = true)
    public List<Pet> getAllPets(Long clientId) {
        return petRepository.findByClientId(clientId);
    }

    /**
     * Мастер обновляет сложность питомца после визита.
     * Например, после первого визита выставляет HARD с пояснением.
     */
    @Transactional
    public Pet updatePetDifficulty(Long petId, Long masterClientId,
                                   PetDifficulty difficulty, String note) {
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new PetNotFoundException(petId));

        PetDifficulty oldDifficulty = pet.getDifficulty();
        pet.updateDifficulty(difficulty, note);
        petRepository.save(pet);

        log.info("Питомец #{} '{}': сложность {} → {}. Заметка: {}",
                petId, pet.getName(), oldDifficulty, difficulty, note);

        return pet;
    }

    /**
     * Деактивировать питомца — soft delete.
     * Питомец пропадает из списка при бронировании, но остаётся в истории броней.
     */
    @Transactional
    public void deactivatePet(Long petId, Long telegramId) {
        Client client = clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));

        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new PetNotFoundException(petId));

        if (!pet.getClient().getId().equals(client.getId())) {
            throw new PetNotFoundException(petId);
        }

        pet.deactivate();
        petRepository.save(pet);

        log.info("Питомец #{} '{}' деактивирован", petId, pet.getName());
    }

    // Приватные методы

    private Client registerNewClient(Long telegramId, String name, String phone) {
        // Телефон должен быть уникальным
        if (clientRepository.existsByPhone(phone)) {
            throw new PhoneAlreadyRegisteredException(phone);
        }

        Client client = Client.builder()
                .telegramId(telegramId)
                .name(name)
                .phone(phone)
                .status(ClientStatus.ACTIVE)
                .noShowCount(0)
                .build();

        clientRepository.save(client);

        log.info("Зарегистрирован новый клиент #{} telegramId={}",
                client.getId(), telegramId);

        return client;
    }
}
