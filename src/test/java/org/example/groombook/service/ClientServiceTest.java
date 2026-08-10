package org.example.groombook.service;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientService")
class ClientServiceTest {

    @Mock
    ClientRepository clientRepository;
    @Mock
    PetRepository petRepository;

    @InjectMocks
    ClientService clientService;

    private Client activeClient;
    private Pet activePet;

    @BeforeEach
    void setUp() {
        activeClient = Client.builder()
                .id(1L)
                .telegramId(100L)
                .name("Иван Петров")
                .phone("+79991234567")
                .status(ClientStatus.ACTIVE)
                .noShowCount(0)
                .build();

        activePet = Pet.builder()
                .id(10L)
                .client(activeClient)
                .name("Рекс")
                .type(PetType.DOG)
                .difficulty(PetDifficulty.EASY)
                .active(true)
                .build();
    }

    // getOrCreateClient

    @Nested
    @DisplayName("getOrCreateClient")
    class GetOrCreateClient {

        @Test
        @DisplayName("✅ клиент уже существует — возвращает существующего без создания нового")
        void getOrCreateClient_existingClient_returnsExisting() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));

            Client result = clientService.getOrCreateClient(100L, "Другое Имя", "+79990000000");

            assertThat(result).isEqualTo(activeClient);
            assertThat(result.getName()).isEqualTo("Иван Петров"); // имя не изменилось
            verify(clientRepository, never()).save(any());
        }

        @Test
        @DisplayName("✅ новый клиент — регистрирует и сохраняет")
        void getOrCreateClient_newClient_registersAndSaves() {
            when(clientRepository.findByTelegramId(200L)).thenReturn(Optional.empty());
            when(clientRepository.existsByPhone("+79998887766")).thenReturn(false);
            when(clientRepository.save(any())).thenAnswer(inv -> {
                Client c = inv.getArgument(0);
                c.setId(2L);
                return c;
            });

            Client result = clientService.getOrCreateClient(200L, "Мария Сидорова", "+79998887766");

            assertThat(result.getTelegramId()).isEqualTo(200L);
            assertThat(result.getName()).isEqualTo("Мария Сидорова");
            assertThat(result.getPhone()).isEqualTo("+79998887766");
            assertThat(result.getStatus()).isEqualTo(ClientStatus.ACTIVE);
            assertThat(result.getNoShowCount()).isEqualTo(0);

            verify(clientRepository).save(any(Client.class));
        }

        @Test
        @DisplayName("❌ телефон занят другим клиентом → PhoneAlreadyRegisteredException")
        void getOrCreateClient_phoneTaken_throwsException() {
            when(clientRepository.findByTelegramId(200L)).thenReturn(Optional.empty());
            when(clientRepository.existsByPhone("+79991234567")).thenReturn(true);

            assertThatThrownBy(() ->
                    clientService.getOrCreateClient(200L, "Новый Клиент", "+79991234567"))
                    .isInstanceOf(PhoneAlreadyRegisteredException.class);

            verify(clientRepository, never()).save(any());
        }
    }

    // isRegistered

    @Nested
    @DisplayName("isRegistered")
    class IsRegistered {

        @Test
        @DisplayName("✅ клиент найден → true")
        void isRegistered_existingClient_returnsTrue() {
            when(clientRepository.existsByTelegramId(100L)).thenReturn(true);

            assertThat(clientService.isRegistered(100L)).isTrue();
        }

        @Test
        @DisplayName("✅ клиент не найден → false")
        void isRegistered_unknownClient_returnsFalse() {
            when(clientRepository.existsByTelegramId(999L)).thenReturn(false);

            assertThat(clientService.isRegistered(999L)).isFalse();
        }
    }

    // findByPhone

    @Nested
    @DisplayName("findByPhone")
    class FindByPhone {

        @Test
        @DisplayName("✅ клиент найден по телефону")
        void findByPhone_found() {
            when(clientRepository.findByPhone("+79991234567"))
                    .thenReturn(Optional.of(activeClient));

            Optional<Client> result = clientService.findByPhone("+79991234567");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Иван Петров");
        }

        @Test
        @DisplayName("✅ клиент не найден → empty")
        void findByPhone_notFound() {
            when(clientRepository.findByPhone("+70000000000")).thenReturn(Optional.empty());

            Optional<Client> result = clientService.findByPhone("+70000000000");

            assertThat(result).isEmpty();
        }
    }

    // changeClientStatus

    @Nested
    @DisplayName("changeClientStatus")
    class ChangeClientStatus {

        @Test
        @DisplayName("✅ ACTIVE → BLOCKED с причиной")
        void changeClientStatus_activeToBlocked() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(activeClient));
            when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Client result = clientService.changeClientStatus(
                    1L, ClientStatus.BLOCKED, "Агрессивное животное");

            assertThat(result.getStatus()).isEqualTo(ClientStatus.BLOCKED);
            assertThat(result.getStatusReason()).isEqualTo("Агрессивное животное");
            verify(clientRepository).save(activeClient);
        }

        @Test
        @DisplayName("✅ ACTIVE → REQUIRES_APPROVAL")
        void changeClientStatus_toRequiresApproval() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(activeClient));
            when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Client result = clientService.changeClientStatus(
                    1L, ClientStatus.REQUIRES_APPROVAL, "2 no-show подряд");

            assertThat(result.getStatus()).isEqualTo(ClientStatus.REQUIRES_APPROVAL);
        }

        @Test
        @DisplayName("✅ BLOCKED → ACTIVE (разблокировка)")
        void changeClientStatus_blockedToActive() {
            activeClient.changeStatus(ClientStatus.BLOCKED, "причина");
            when(clientRepository.findById(1L)).thenReturn(Optional.of(activeClient));
            when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Client result = clientService.changeClientStatus(1L, ClientStatus.ACTIVE, null);

            assertThat(result.getStatus()).isEqualTo(ClientStatus.ACTIVE);
            assertThat(result.isBlocked()).isFalse();
        }

        @Test
        @DisplayName("❌ клиент не найден → ClientNotFoundException")
        void changeClientStatus_clientNotFound_throwsException() {
            when(clientRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    clientService.changeClientStatus(999L, ClientStatus.BLOCKED, "причина"))
                    .isInstanceOf(ClientNotFoundException.class);
        }
    }

    // addPet

    @Nested
    @DisplayName("addPet")
    class AddPet {

        @Test
        @DisplayName("✅ питомец добавляется с difficulty EASY по умолчанию")
        void addPet_success_defaultDifficultyEasy() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Pet result = clientService.addPet(100L, "Пушок", PetType.CAT, "Мейн-кун");

            assertThat(result.getName()).isEqualTo("Пушок");
            assertThat(result.getType()).isEqualTo(PetType.CAT);
            assertThat(result.getBreed()).isEqualTo("Мейн-кун");
            assertThat(result.getDifficulty()).isEqualTo(PetDifficulty.EASY);
            assertThat(result.isActive()).isTrue();
            assertThat(result.getClient()).isEqualTo(activeClient);

            verify(petRepository).save(any(Pet.class));
        }

        @Test
        @DisplayName("✅ порода необязательна — null допустим")
        void addPet_withoutBreed_success() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Pet result = clientService.addPet(100L, "Шарик", PetType.DOG, null);

            assertThat(result.getBreed()).isNull();
        }

        @Test
        @DisplayName("❌ клиент не найден → ClientNotFoundException")
        void addPet_clientNotFound_throwsException() {
            when(clientRepository.findByTelegramId(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    clientService.addPet(999L, "Барсик", PetType.CAT, null))
                    .isInstanceOf(ClientNotFoundException.class);

            verify(petRepository, never()).save(any());
        }
    }

    // getActivePets

    @Nested
    @DisplayName("getActivePets")
    class GetActivePets {

        @Test
        @DisplayName("✅ возвращает только активных питомцев клиента")
        void getActivePets_returnsOnlyActive() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.findByClientIdAndActiveTrue(1L))
                    .thenReturn(List.of(activePet));

            List<Pet> result = clientService.getActivePets(100L);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getName()).isEqualTo("Рекс");
        }

        @Test
        @DisplayName("✅ нет активных питомцев — пустой список")
        void getActivePets_noPets_returnsEmpty() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.findByClientIdAndActiveTrue(1L))
                    .thenReturn(List.of());

            List<Pet> result = clientService.getActivePets(100L);

            assertThat(result).isEmpty();
        }
    }

    // updatePetDifficulty

    @Nested
    @DisplayName("updatePetDifficulty")
    class UpdatePetDifficulty {

        @Test
        @DisplayName("✅ EASY → HARD с заметкой")
        void updatePetDifficulty_easyToHard() {
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Pet result = clientService.updatePetDifficulty(
                    10L, 1L, PetDifficulty.HARD, "Кусается при стрижке лап");

            assertThat(result.getDifficulty()).isEqualTo(PetDifficulty.HARD);
            assertThat(result.getDifficultyNote()).isEqualTo("Кусается при стрижке лап");
            verify(petRepository).save(activePet);
        }

        @Test
        @DisplayName("✅ EASY → REFUSED (отказ от работы)")
        void updatePetDifficulty_toRefused() {
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Pet result = clientService.updatePetDifficulty(
                    10L, 1L, PetDifficulty.REFUSED, "Агрессивная собака, опасна");

            assertThat(result.isRefused()).isTrue();
        }

        @Test
        @DisplayName("❌ питомец не найден → PetNotFoundException")
        void updatePetDifficulty_petNotFound() {
            when(petRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    clientService.updatePetDifficulty(999L, 1L, PetDifficulty.HARD, "заметка"))
                    .isInstanceOf(PetNotFoundException.class);
        }
    }

    // deactivatePet

    @Nested
    @DisplayName("deactivatePet")
    class DeactivatePet {

        @Test
        @DisplayName("✅ питомец деактивирован (soft delete)")
        void deactivatePet_success() {
            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.findById(10L)).thenReturn(Optional.of(activePet));
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            clientService.deactivatePet(10L, 100L);

            assertThat(activePet.isActive()).isFalse();
            verify(petRepository).save(activePet);
        }

        @Test
        @DisplayName("❌ попытка деактивировать чужого питомца → PetNotFoundException")
        void deactivatePet_foreignPet_throwsException() {
            Client otherClient = Client.builder()
                    .id(2L).telegramId(200L).name("Другой").phone("+70000000001")
                    .status(ClientStatus.ACTIVE).build();

            Pet foreignPet = Pet.builder()
                    .id(20L).client(otherClient).name("Чужой")
                    .type(PetType.CAT).active(true).build();

            when(clientRepository.findByTelegramId(100L))
                    .thenReturn(Optional.of(activeClient));
            when(petRepository.findById(20L)).thenReturn(Optional.of(foreignPet));

            assertThatThrownBy(() -> clientService.deactivatePet(20L, 100L))
                    .isInstanceOf(PetNotFoundException.class);

            verify(petRepository, never()).save(any());
        }
    }
}
