package org.example.groombook.bot;

import org.example.groombook.bot.handler.ClientHandler;
import org.example.groombook.bot.handler.MasterHandler;
import org.example.groombook.bot.session.SessionManager;
import org.example.groombook.bot.session.SessionState;
import org.example.groombook.bot.session.UserSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateDispatcherTest {

    @Mock
    private ClientHandler clientHandler;
    @Mock
    private MasterHandler masterHandler;
    @Mock
    private SessionManager sessionManager;

    @InjectMocks
    private UpdateDispatcher updateDispatcher;

    private final Long MASTER_ID = 123L;
    private final Long CLIENT_ID = 456L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(updateDispatcher, "masterTelegramId", MASTER_ID);
    }

    @Test
    void dispatch_callbackFromMaster_shouldCallMasterHandler() {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        User user = mock(User.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getFrom()).thenReturn(user);
        when(user.getId()).thenReturn(MASTER_ID);
        when(callbackQuery.getData()).thenReturn("CONFIRM_BOOKING:1");
        when(callbackQuery.getId()).thenReturn("cb_1");

        updateDispatcher.dispatch(update);

        verify(masterHandler).handleCallback(MASTER_ID, "CONFIRM_BOOKING:1", "cb_1");
        verifyNoInteractions(clientHandler);
    }

    @Test
    void dispatch_callbackFromClient_shouldCallClientHandler() {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        User user = mock(User.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getFrom()).thenReturn(user);
        when(user.getId()).thenReturn(CLIENT_ID);
        when(callbackQuery.getData()).thenReturn("BOOK_SLOT:1");
        when(callbackQuery.getId()).thenReturn("cb_2");

        updateDispatcher.dispatch(update);

        verify(clientHandler).handleCallback(CLIENT_ID, "BOOK_SLOT:1", "cb_2");
        verifyNoInteractions(masterHandler);
    }

    @Test
    void dispatch_messageCommandFromMaster_shouldClearSessionAndCallMasterHandler() {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        UserSession session = new UserSession();

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(MASTER_ID);
        when(sessionManager.get(MASTER_ID)).thenReturn(session);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/start");

        updateDispatcher.dispatch(update);

        verify(sessionManager).clear(MASTER_ID);
        verify(masterHandler).handleCommand(MASTER_ID, "/start");
    }

    @Test
    void dispatch_weeklyReportButtonFromMaster_shouldClearSessionAndCallMasterHandler() {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        UserSession session = new UserSession();

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(MASTER_ID);
        when(sessionManager.get(MASTER_ID)).thenReturn(session);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("📊 Отчет за неделю");

        updateDispatcher.dispatch(update);

        verify(sessionManager).clear(MASTER_ID);
        verify(masterHandler).handleCommand(MASTER_ID, "📊 Отчет за неделю");
    }

    @Test
    void dispatch_textInputFromClientInState_shouldCallClientHandlerTextInput() {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        UserSession session = new UserSession();
        session.setState(SessionState.AWAITING_PET_NAME);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CLIENT_ID);
        when(sessionManager.get(CLIENT_ID)).thenReturn(session);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("Buddy");

        updateDispatcher.dispatch(update);

        verify(clientHandler).handleTextInput(CLIENT_ID, "Buddy", SessionState.AWAITING_PET_NAME);
    }

    @Test
    void dispatch_exceptionInMasterFlow_shouldSendMasterErrorMessage() {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        UserSession session = new UserSession();

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(MASTER_ID);
        when(sessionManager.get(MASTER_ID)).thenReturn(session);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/start");

        // Генерируем исключение при вызове хендлера
        doThrow(new RuntimeException("Test error")).when(masterHandler).handleCommand(MASTER_ID, "/start");

        updateDispatcher.dispatch(update);

        // Проверяем, что вызвана отправка ошибки мастеру
        verify(masterHandler).send(MASTER_ID, "❌ Произошла системная ошибка при обработке вашего запроса. Пожалуйста, попробуйте позже.");
    }

    @Test
    void dispatch_exceptionInClientFlow_shouldSendClientErrorMessage() {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        UserSession session = new UserSession();

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CLIENT_ID);
        when(sessionManager.get(CLIENT_ID)).thenReturn(session);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/start");

        doThrow(new RuntimeException("Test error")).when(clientHandler).handleCommand(CLIENT_ID, "/start");

        updateDispatcher.dispatch(update);

        // Проверяем, что вызвана отправка ошибки клиенту
        verify(clientHandler).send(CLIENT_ID, "❌ Извините, произошла ошибка при обработке вашего запроса. Попробуйте еще раз или свяжитесь с мастером напрямую.");
    }

    @Test
    void dispatch_exceptionInCallback_shouldSendErrorMessage() {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        User user = mock(User.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getFrom()).thenReturn(user);
        when(user.getId()).thenReturn(CLIENT_ID);
        when(callbackQuery.getData()).thenReturn("data");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(CLIENT_ID);

        doThrow(new RuntimeException("Test error")).when(clientHandler).handleCallback(eq(CLIENT_ID), eq("data"), anyString());

        updateDispatcher.dispatch(update);

        verify(clientHandler).send(CLIENT_ID, "❌ Извините, произошла ошибка при обработке вашего запроса. Попробуйте еще раз или свяжитесь с мастером напрямую.");
    }
}
