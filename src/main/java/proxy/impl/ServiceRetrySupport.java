package proxy.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.ServiceCall;
import proxy.api.ServiceNotAvailableException;
import proxy.api.ServiceRetry;
import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.io.IOException;
import java.util.List;

/**
 * Инициирует вызов сервиса с повторными попытками (retry), если сервис недоступен, то есть если
 * генерируется исключение {@link ServiceNotAvailableException}. Повторные попытки будут
 * продолжаться в течение {@link #serviceWaitTimeMs}. Если сервис просто возвращает ошибку - то retry не будет.
 *
 * @author rushan
 */
public class ServiceRetrySupport implements ServiceRetry {

    private final Logger log = LoggerFactory.getLogger(ServiceRetrySupport.class);

    private final ServiceCall serviceCall;

    private final int threshold;
    private final int timeBetweenCallMs;
    private final int serviceWaitTimeMs;

    private final BatchSupport batchSupport = new BatchSupport();

    public ServiceRetrySupport(ServiceCall serviceCall, int threshold, int timeBetweenCallMs, int serviceWaitTimeMs) {
        this.serviceCall = serviceCall;
        this.threshold = threshold;
        this.timeBetweenCallMs = timeBetweenCallMs;
        this.serviceWaitTimeMs = serviceWaitTimeMs;
    }

    @Override
    public List<ServiceResponse> call(List<Message> messages) throws IOException, ServiceNotAvailableException {
        byte[] batchData = batchSupport.toBatchBuffer(messages);
        ServiceResponse batchResponse = callService(batchData);
        List<ServiceResponse> result = batchSupport.splitBatch(batchResponse);
        if (result.size() != messages.size()) {
            throw new RuntimeException("Unexpected service response. The response result size is not match to batch size");
        }
        return result;
    }

    private ServiceResponse callService(byte[] data) throws ServiceNotAvailableException {
        long startTime = System.currentTimeMillis();
        long waitTime;
        Exception last;
        int count = 0;
        do {
            try {
                return serviceCall.call(data);
            } catch (IOException e) {
                // Ретраим только в случае коммуникационых ошибок (ConnectException, UnknownHostException,
                // SocketTimeoutException, SocketException...).
                // Если вызов прошел успешно со статусом, отличным от 200, то ретраить смысла нет, так как потребуется
                // скорее всего изменение запроса от клиента или невозможности такого запроса. Например, 401 может
                // требовать предоставить корректный токен, 400 - неправильно сформирован запрос и т.д.
                last = e;
                log.error("Service call {} is failed. Details: {}", serviceCall, e.getMessage());

                // Задержка только в случаях превышения threshold
                if (++count > threshold - 1) {
                    try {
                        long sleepTime = Math.min(this.serviceWaitTimeMs, this.timeBetweenCallMs);
                        log.debug("Perform sleep on {}ms", sleepTime);

                        // Никакого "busy wait", как пишет idea, не будет. Начиная с версии ядра linux 2.x sleep
                        // реализован путем хинтов планировщику.
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } while ((waitTime = System.currentTimeMillis() - startTime) < this.serviceWaitTimeMs
                && !Thread.currentThread().isInterrupted());

        throw new ServiceNotAvailableException(waitTime, count, last);
    }
}
