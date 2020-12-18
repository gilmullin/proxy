package proxy.model;

import java.time.Instant;
import java.util.Base64;

/**
 * Сообщение, полученное проксей от клиента (данные, переданные за один вызов его api).
 *
 * <p>Внутренняя структура сообщения не интересна (идентификатор клиента/устройства и т.д.), поэтому
 * все хранится в нетипизированном виде в {@link #getData()}. Также там может хранится и всякая другая идентифицирующая
 * информация - подписанный токен безопасности (например) или идентификатор сессии и т.д. Прокси-сервер не отвечает
 * за аутентификацию и авторизацию, поэтому не нуждается в интерпретации этих данных. В реальном приложении,
 * возможно, появилась бы необходимость отдельным образом представлять заголовки запроса, здесь этого избегаю
 * для упрощения.
 *
 * @author rushan
 */
public class Message {
    private final Instant receivedAt;
    private final byte[] data;

    public Message(Instant receivedAt, byte[] data) {
        this.receivedAt = receivedAt;
        this.data = data;
    }

    /**
     * Время, когда было получено сообщение.
     */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    /**
     * Данные, переданные в сообщении.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Тело сообщения в base64 для логгирования
     */
    public String dataToBase64() {
        return Base64.getEncoder().encodeToString(this.data);
    }
}
