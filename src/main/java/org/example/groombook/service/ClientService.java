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
import java.util.Optional;

/**
 * Сервис управления профилями клиентов и их питомцами.
 * <p>
 * Обеспечивает логику регистрации пользователей, проверки их статусов, добавления
 * и деактивации питомцев, а также ведения служебных заметок мастера (например, уровня сложности ухода).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final PetRepository petRepository;

    // Регистрация и поиск клиента

    /**
     * Возвращает существующего клиента или регистрирует нового.
     * <p>
     * Инициализируется при каждом первичном обращении пользователя к Telegram-боту.
     */
    @Transactional
    public Client getOrCreateClient(Long telegramId, String name, String phone) {
        return clientRepository.findByTelegramId(telegramId)
                .orElseGet(() -> registerNewClient(telegramId, name, phone));
    }

    /**
     * Проверяет факт наличия зарегистрированного профиля по Telegram ID.
     * Используется обработчиком команды {@code /start} для предотвращения повторной анкетирования.
     */
    @Transactional(readOnly = true)
    public boolean isRegistered(Long telegramId) {
        return clientRepository.existsByTelegramId(telegramId);
    }

    /**
     * Получает сущность клиента по его Telegram ID.
     */
    @Transactional(readOnly = true)
    public Client getByTelegramId(Long telegramId) {
        return clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));
    }

    /**
     * Поиск клиента по внутреннему первичному ключу БД.
     */
    @Transactional(readOnly = true)
    public Client getById(Long clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    /**
     * Возвращает полный список всех зарегистрированных клиентов.
     * Используется мастером при выгрузках и оформлении manual-записей.
     */
    @Transactional(readOnly = true)
    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    /**
     * Поиск профиля клиента по номеру телефона.
     * Применяется при ручном создании запись мастером (команда {@code /manual}).
     */
    @Transactional(readOnly = true)
    public Optional<Client> findByPhone(String phone) {
        return clientRepository.findByPhone(phone);
    }

    // Управление статусом клиента — только мастер, только вручную

    /**
     * Изменяет текущий статус клиента (например, блокировка и т.д.).
     * <p>
     * Выполняется исключительно мастером вручную. Причина изменения фиксируется в БД.
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
     * Формирует список клиентов, у которых не было посещений за последние N дней.
     * Используется для маркетингового анализа и рассылок мастера.
     */
    @Transactional(readOnly = true)
    public List<Client> getInactiveClients(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return clientRepository.findInactiveClients(since);
    }

    // Управление питомцами

    /**
     * Привязывает нового питомца к профилю клиента.
     * Вызывается при первичной регистрации или при вызове команды {@code /addpet}.
     * По умолчанию питомцу присваивается базовая сложность {@link PetDifficulty#EASY}.
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
     * Возвращает список только активных питомцев клиента.
     * Используется при выборе питомца в процессе создания записи.
     */
    @Transactional(readOnly = true)
    public List<Pet> getActivePets(Long telegramId) {
        Client client = clientRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new ClientNotFoundException(telegramId));
        return petRepository.findByClientIdAndActiveTrue(client.getId());
    }

    /**
     * Возвращает полный список питомцев клиента, включая архивных (деактивированных).
     * Служит для поднятия истории приемов.
     */
    @Transactional(readOnly = true)
    public List<Pet> getAllPets(Long clientId) {
        return petRepository.findByClientId(clientId);
    }

    /**
     * Обновляет уровень сложности груминга питомца и добавляет заметки мастером.
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
     * Выполняет мягкое удаление (деактивацию) питомца из профиля клиента.
     * Питомец перестает отображаться при создании бронирований, но остается в исторической отчетности.
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

    /**
     * Создает и сохраняет новую запись клиента в БД с проверкой уникальности телефона.

     */
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
