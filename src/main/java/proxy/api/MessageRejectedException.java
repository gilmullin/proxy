package proxy.api;

/**
 * Данное исключение генерируется, если сообщение отклоняется - размер очереди сообщений превышен.
 */
public class MessageRejectedException extends Exception {
    public MessageRejectedException(String message) {
        super(message);
    }
}
