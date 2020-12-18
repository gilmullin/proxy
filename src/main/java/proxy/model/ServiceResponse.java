package proxy.model;

import java.util.Base64;

/**
 * Ответ сервиса. В реальном приложении сюда можно добавить contentType, character encoding и др. заголовки.
 *
 * @author rushan
 */
public class ServiceResponse {
    private final int status;
    private final byte[] data;

    private ServiceResponse(int status, byte[] data) {
        this.status = status;
        this.data = data;
    }

    public static ServiceResponse response(int status, byte[] data) {
        return new ServiceResponse(status, data);
    }

    public static ServiceResponse ok(byte[] data) {
        return new ServiceResponse(200, data);
    }

    public static ServiceResponse empty() {
        return new ServiceResponse(-1, new byte[0]);
    }

    public int getStatus() {
        return status;
    }

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
