package proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.ServiceCall;
import proxy.api.ServiceRetry;
import proxy.impl.AsyncProxy;
import proxy.impl.BlockingHttpCall;
import proxy.impl.NoServiceCall;
import proxy.impl.ServiceRetrySupport;
import proxy.util.Util;
import proxy.web.WebServer;

import java.util.Map;
import java.util.Optional;

/**
 * Вводные: есть клиенты и бекэнд, взаимодействие происходит по rest.  Возникающие нештатные ситуации: бекенд
 * оказывается недоступным на N секунд, за это время клиенты отправляют 100-1000 сообщений системе Задача: реализовать
 * кеширующую прокси с очередью, обеспечить гарантирующее доставку всех запросов на бекенд после его восстановления.
 * Использовать только java без сторонних библиотек. Приложение должно быть многопоточным с функциями кеширования.
 *
 * @author rushan
 */
public class Proxy {

    public static final int BATCH_SIZE_DEFAULT = 10_000;
    public static final int SERVICE_WAIT_THRESHOLD_DEFAULT = 5;
    public static final int SERVICE_WAIT_TIME_MS_DEFAULT = 10_000;
    public static final int TIME_BETWEEN_SERVICE_CALL_MS_DEFAULT = 100;

    public static final int MAX_JETTY_THREADS = 8;
    public static final int MIN_JETTY_THREADS = 2;

    public static final int SERVICE_CALL_IO_THREADS_DEFAULT = 4;

    private static final int SERVICE_CALL_QUEUE_MAX_SIZE_DEFAULT = 10_000;

    private static final Logger log = LoggerFactory.getLogger(Proxy.class);
    public static final String PROXY_DEFAULT_PATH = "/message";

    private final AsyncProxy asyncProxy;
    private final WebServer webServer;

    public static void main(String[] argv) {
        Map<String, String> args = Util.readArguments(argv);

        Proxy proxy = new Proxy(args);

        try {
            proxy.start();
        } catch (Exception e) {
            log.error("Failed to start proxy server", e);
            return;
        }

        // Реализуем корректное завершение процесса, что позволит, например, корректно завершаться при SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::dispose));
    }

    public Proxy(Map<String, String> args) {

        String serviceUrl = args.get("serviceUrl");

        int batchSize = Optional.ofNullable(args.get("batchSize")).map(Integer::parseInt)
                .orElse(BATCH_SIZE_DEFAULT);

        // Количество ретраев сервисе без ожидания
        int serviceWaitThreshold = Optional.ofNullable(args.get("serviceWaitThreshold")).map(Integer::parseInt)
                .orElse(SERVICE_WAIT_THRESHOLD_DEFAULT);

        // N секунд ожидания сервиса из условия задачи (в миллисекундах)
        int serviceWaitTimeMs = Optional.ofNullable(args.get("serviceWaitTimeMs")).map(Integer::parseInt)
                .orElse(SERVICE_WAIT_TIME_MS_DEFAULT);

        // Время ожидания между неудачными попытками в мс. Если 0 - то нет ожидания.
        int timeBetweenServiceCallMs = Optional.ofNullable(args.get("timeBetweenServiceCallMs")).map(Integer::parseInt)
                .orElse(TIME_BETWEEN_SERVICE_CALL_MS_DEFAULT);

        int minJettyThreads = Optional.ofNullable(args.get("minJettyThreads")).map(Integer::parseInt)
                .orElse(MIN_JETTY_THREADS);
        int maxJettyThreads = Optional.ofNullable(args.get("maxJettyThreads")).map(Integer::parseInt)
                .orElse(MAX_JETTY_THREADS);

        int port = Optional.ofNullable(args.get("port")).map(Integer::parseInt).orElse(8080);

        String proxyPath = args.getOrDefault("proxyPath", PROXY_DEFAULT_PATH);

        int ioThreads = Optional.ofNullable(args.get("ioThreads")).map(Integer::parseInt)
                .orElse(SERVICE_CALL_IO_THREADS_DEFAULT);
        int maxMessageQueueSize = Optional.ofNullable(args.get("maxMessageQueueSize")).map(Integer::parseInt)
                .orElse(SERVICE_CALL_QUEUE_MAX_SIZE_DEFAULT);

        ServiceCall serviceCall = serviceUrl != null
                ? new BlockingHttpCall(serviceUrl, serviceWaitTimeMs, serviceWaitTimeMs)
                : new NoServiceCall();

        ServiceRetry serviceRetry = new ServiceRetrySupport(serviceCall,
                serviceWaitThreshold,
                serviceWaitTimeMs,
                timeBetweenServiceCallMs);

        asyncProxy = new AsyncProxy(
                serviceRetry,
                ioThreads,
                maxMessageQueueSize,
                batchSize);

        webServer = new WebServer(port, proxyPath, minJettyThreads, maxJettyThreads, asyncProxy);
    }

    public void start() throws InterruptedException {
        try {
            webServer.start();
        } catch (Exception e) {
            log.error("Failed to start web server. Details: {}", e.getMessage(), e);
            asyncProxy.dispose();
        }
    }

    public void dispose() {
        log.info("Disposing proxy");

        try {
            this.webServer.stop();
            log.info("Jetty is stopped");
        } catch (Exception e) {
            log.error("Failed to stop jetty", e);
        }

        try {
            this.asyncProxy.dispose();
            log.info("Async proxy is stopped");
        } catch (InterruptedException e) {
            log.error("Failed to wait for async proxy disposing. The shutdown thread was interrupted");
        }
    }
}
