package proxy.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.MessageRejectedException;
import proxy.api.ServiceNotAvailableException;
import proxy.impl.AsyncProxy;
import proxy.model.Message;
import proxy.model.ServiceResponse;
import proxy.util.Util;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Сетевой API. Получает сообщение от клиента/устройства и инициирует асинхронное обращение к проксируемому
 * сервису с использованием заданного {@link #proxy}. Если проксируемый сервис не даст ответ в течение
 * заданного периода {@link #CLIENT_TIMEOUT_MS}, выдаст отказ.
 *
 * <p>Асинхронная обработка означает, что не блокируется пул потоков веб-сервера (jetty). Если бы обработка была
 * синхронной, то при ожидании ответа сервиса потоки из пула будут выбраны вплоть до верхнего предела
 * и новые запросы повиснут в очереди. При асинхронной обработке они инициируют обращение к сервису и затем
 * станут доступны для обработки новых входящих запросов.
 *
 * @author rushan
 */
public class MessageServlet extends HttpServlet {

    /**
     * Максимальный допустимый размер тела запроса (в байтах). При превышении будет отказ.
     */
    public static final int MESSAGE_MAX_SIZE_BYTES = Integer.MAX_VALUE;

    /**
     * Время ожидания ответа проксируемого сервиса. При превышении прокся выдаст отказ.
     */
    public static final long CLIENT_TIMEOUT_MS = 120_000;

    private static final Logger log = LoggerFactory.getLogger(MessageServlet.class);

    private final AsyncProxy proxy;

    public MessageServlet(AsyncProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!req.isAsyncSupported()) {
            log.error("Unexpected error: async is not supported. Configure jetty to add continuations support");
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        byte[] messageBody = Util.readMessageBody(req.getInputStream(), MESSAGE_MAX_SIZE_BYTES);

        Message message = new Message(Instant.now(), messageBody);

        try {
            doPostInternal(message, req, resp);
        } catch (MessageRejectedException e) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            log.error("The client message is rejected. Possible reasons: the service is busy or unavailable. Message: {}",
                    message.dataToBase64());
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error("Failed to process client message: {}. Details: {}",
                    message.dataToBase64(),
                    e.getMessage(),
                    e);
        }
    }

    private void doPostInternal(Message message, HttpServletRequest req, HttpServletResponse resp)
            throws IOException, MessageRejectedException
    {
        // Вызов может бросить MessageRejectedException, если сообщение отклонено
        CompletableFuture<ServiceResponse> serviceResponseFuture = proxy.message(message);

        AsyncContext asyncContext = req.startAsync();
        // Задаем таймаут - если футура не завершится за TIMEOUT_MS, то запрос будет завершен с
        // ошибкой (это сделает jetty).
        asyncContext.setTimeout(CLIENT_TIMEOUT_MS);

        ServletOutputStream stream = resp.getOutputStream();

        serviceResponseFuture.whenComplete((serviceResponse, ex) -> {
            try {
                if (ex instanceof ServiceNotAvailableException) {
                    resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                } else if (ex != null) {
                    log.error("Unexpected error when sending message to service. Input message: {}",
                            message.dataToBase64(),
                            ex);
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                }

                // Установим статус ответа проксируемого сервиса
                resp.setStatus(resp.getStatus());

                // В реальном приложении content type и encoding необходимо извлечь из ответа сервиса ServiceResponse
                resp.setContentType("application/json");
                resp.setCharacterEncoding("UTF-8");

                try {
                    stream.write(serviceResponse.getData());
                } catch (IOException e) {
                    log.error("Failed to write service response to client stream, input message: {}, service response: {}",
                            message.dataToBase64(),
                            serviceResponse.dataToBase64(),
                            e);
                }
            } finally {
                asyncContext.complete();
            }
        });
    }
}
