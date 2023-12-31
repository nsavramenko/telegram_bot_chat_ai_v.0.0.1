package com.example.telegram_bot_chat_ai.listener;

import com.example.telegram_bot_chat_ai.constant.*;
import com.example.telegram_bot_chat_ai.dto.*;
import com.example.telegram_bot_chat_ai.entity.User;
import com.example.telegram_bot_chat_ai.entity.*;
import com.example.telegram_bot_chat_ai.service.*;
import com.pengrad.telegrambot.*;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.*;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.*;
import jakarta.annotation.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.web.client.*;

import java.io.*;
import java.util.*;

/**
 * The main service of the bot containing the logic of processing incoming updates
 */
@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    /**
     * event recording process
     */
    private final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);

    private final TelegramBot telegramBot;
    private final ChatConfigService chatConfigService;

    private final UserService userService;

    @Qualifier("openaiRestTemplate")
    private final RestTemplate restTemplate;

    public TelegramBotUpdatesListener(TelegramBot telegramBot, ChatConfigService chatConfigService, UserService userService, RestTemplate restTemplate) {
        this.telegramBot = telegramBot;
        this.chatConfigService = chatConfigService;
        this.userService = userService;
        this.restTemplate = restTemplate;
    }

    @Value("${openai.model}")
    private String model;

    @Value("${openai.api.url}")
    private String apiUrl;

    @PostConstruct
    public void init() {
        telegramBot.setUpdatesListener(this);
    }

    /**
     * defining bot actions depending on user status
     */
    @Override
    public int process(List<Update> updates) {
        updates.forEach(update -> {
            // Processing updates
            Long chatId = 0L;
            String firstName = null, lastName = null, nickName = null;
            ChatState chatState;

            UpdateType updateType = checkingUpdate(update);
            if(updateType == UpdateType.COMMAND || updateType == UpdateType.MESSAGE || updateType == UpdateType.PHOTO) {
                chatId = update.message().from().id();
                firstName = update.message().from().firstName();
                lastName = update.message().from().lastName();
                nickName = update.message().from().username();
            } else if(updateType == UpdateType.CALL_BACK_QUERY) {
                chatId = update.callbackQuery().from().id();
                firstName = update.callbackQuery().from().firstName();
                lastName = update.callbackQuery().from().lastName();
                nickName = update.callbackQuery().from().username();
            }

            Optional<ChatConfig> chatConfigResult = chatConfigService.findByChatId(chatId);
            Optional<User> userResult = userService.findByChatId(chatId);
            User user;
            ChatConfig chatConfig;

            if(chatConfigResult.isEmpty() && userResult.isEmpty()) { // New user
                user = new User(firstName, lastName, nickName, chatId);
                userService.addUser(user);
                chatConfig = new ChatConfig(chatId, ChatState.EXISTING_USER);
                chatConfigService.addChatConfig(chatConfig);
                chatState = ChatState.NEW_USER;
            } else { // Not new user
                user = userResult.get();
                chatConfig = chatConfigResult.get();
                chatState = chatConfig.getChatState();
            }

            switch(chatState) {
                case NEW_USER:
                    sendMessage(chatId, Messages.WELCOME_TO_THE_CHATBOT.messageText);
                    break;
                case EXISTING_USER:
                    existingUserUpdateProcessing(chatId, update, updateType, user, chatConfig);
                    break;
                case ZERO_STATE:
            }
        });
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    /**
     * Process Telegram update of existing user
     * @param chatId - Telegram chat id of given update
     * @param update - Telegram update
     * @param updateType - update type from UpdateType enum
     * @param user - user record from the database
     * @param chatConfig - chat config record from the database
     */
    private void existingUserUpdateProcessing(Long chatId, Update update, UpdateType updateType, User user, ChatConfig chatConfig) {


        if(updateType == UpdateType.MESSAGE) {

            // create a request
            ChatRequest request = new ChatRequest(model, update.message().text());

            // call the API
            ChatResponse response = null;
            try {
                response = restTemplate.postForObject(apiUrl, request, ChatResponse.class);
            } catch(RestClientException e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                String stackTrace = sw.toString();
                logger.error("Error during restTemplate.postForObject method invocation:\n" + stackTrace);
                sendMessage(chatId, Messages.OH_SOMETHING_GOT_WRONG.messageText + e.getMessage()
                        + Messages.YOU_NEED_TO_CALL_SUPPORT.messageText);
            }

            if(response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                sendMessage(chatId, Messages.NO_RESPONSE.messageText);
            }

            // send user back the first response from chatGPT
            sendMessage(chatId, response.getChoices().get(0).getMessage().getContent());

        } else if(updateType == UpdateType.COMMAND &&
                update.message().text().equalsIgnoreCase(Commands.START.commandText)) {
            sendMessage(chatId, Messages.WELL_ONE_MORE_TIME.messageText);
            sendMessage(chatId, Messages.WELCOME_TO_THE_CHATBOT.messageText);
        } else if ((updateType == UpdateType.COMMAND &&
                update.message().text().equalsIgnoreCase(Commands.HELP.commandText)) ||
                (updateType == UpdateType.CALL_BACK_QUERY &&
                        update.callbackQuery().data().equals(Buttons.HELP.bCallBack))) {
            sendMessage(chatId, Messages.HELP.messageText);
        } else if (updateType == UpdateType.COMMAND &&
                update.message().text().equalsIgnoreCase(Commands.MENU.commandText)) {
            sendMenu(chatId, Messages.CONTEXT_MENU.messageText, Buttons.SUPPORT, Buttons.SAFETY, Buttons.HELP);
        } else if ((updateType == UpdateType.COMMAND &&
                update.message().text().equalsIgnoreCase(Commands.SAFETY.commandText)) ||
                (updateType == UpdateType.CALL_BACK_QUERY &&
                        update.callbackQuery().data().equals(Buttons.SAFETY.bCallBack))){
            sendMessage(chatId, Messages.SAFETY.messageText);
        } else if ((updateType == UpdateType.COMMAND &&
                update.message().text().equalsIgnoreCase(Commands.SUPPORT.commandText)) ||
                (updateType == UpdateType.CALL_BACK_QUERY &&
                        update.callbackQuery().data().equals(Buttons.SUPPORT.bCallBack))){
            sendMessage(chatId, Messages.SUPPORT.messageText);
        } else if(updateType == UpdateType.COMMAND){
            sendMessage(chatId, Messages.THERE_IS_NO_SUCH_COMMAND.messageText);
            sendMessage(chatId, Messages.HELP.messageText);
        } else if(updateType == UpdateType.PHOTO){
            sendMessage(chatId, Messages.I_CANNOT_PROCESS_PHOTOS.messageText);
            sendMessage(chatId, Messages.HELP.messageText);
        }

    }

    /**
     * Checking type of Telegram update
     * @return UpdateType enum value
     * @param update Telegram Update class object
     */
    private UpdateType checkingUpdate(Update update) {
        if (update.message() != null) {
            if(update.message().text() != null) {
                if (update.message().text().startsWith("/")) {
                    return UpdateType.COMMAND;
                } else {
                    return UpdateType.MESSAGE;
                }
            } else if (update.message().photo() != null) {
                return UpdateType.PHOTO;
            } else {
                return UpdateType.ERROR;
            }

        } else if (update.callbackQuery() != null) { // Callback answer processing
            return UpdateType.CALL_BACK_QUERY;

        } else {
            return UpdateType.ERROR;
        }
    }

    /**
     * Send message to Telegram user with chatId
     * @param chatId chat id
     * @param message message text to send
     */
    private void sendMessage(Long chatId, String message) {
        SendMessage sendMessage = new SendMessage(chatId, message);
        SendResponse response = telegramBot.execute(sendMessage);
        if(response != null && !response.isOk()) {
            logger.error("Message was not sent. Error code:  " + response.errorCode());
        } else if(response == null) {
            logger.error("Message was not sent. Response is null");
        }
    }

    /**
     * Send menu to Telegram user with chatId
     * @param chatId chat id
     * @param menuHeader - menu header text to display before menu buttons
     * @param buttons -  buttons array of Buttons type
     */
    private void sendMenu(Long chatId, String menuHeader, Buttons... buttons) {
        InlineKeyboardButton[][] inlineKeyboardButtons = new InlineKeyboardButton[buttons.length][1];
        for(int i = 0; i < buttons.length; i++) {
            if(buttons[i].bType.equals(ButtonType.CALLBACK)) {
                inlineKeyboardButtons[i][0] = new InlineKeyboardButton(buttons[i].bText).callbackData(buttons[i].bCallBack);
            } else {
                inlineKeyboardButtons[i][0] = new InlineKeyboardButton(buttons[i].bText).url(buttons[i].url);
            }
        }
        InlineKeyboardMarkup inlineKeyboard = new InlineKeyboardMarkup(inlineKeyboardButtons);
        SendMessage message = new SendMessage(chatId, menuHeader);
        message.replyMarkup(inlineKeyboard);
        SendResponse response = telegramBot.execute(message);
        if (response != null && !response.isOk()){
            logger.error("Response error: {} {}", response.errorCode(), response.message());
        } else {
            logger.error("Response error: response is null");
        }
    }

}