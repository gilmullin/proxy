package proxy.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.MessageRejectedException;
import proxy.api.ServiceRetry;
import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * Асинхронный прокси, доставляющая сообщения {@link proxy.model.Message} до проксируемого сервиса. Сообщения
 * посылаются в батч-пакетах.
 *
 * <p>Гарантированную доставку сообщений реализовать невозможно в принципе, если пропускная
 * способность проксируемого сервиса уступает скорости поступления сообщений. Если сообщений в среднем поступает
 * больше, чем обрабатывается, то без отклонений (reject) некоторых из входящих соединений (до уравнивания этих двух
 * показателей) размер очереди будет расти, пока не достигнет пределов памяти (а если сохранять на диск, то пределов
 * емкости дисков). Увеличение допустимых размеров этой очереди лишь усугубит проблему - проксируемый сервис никогда не
 * обработает накопленные сообщения (это может произойти только лишь при увеличении ресурсов, выделяемых сервису),
 * таким образом латентность будет расти до бесконечности. Таким образом, необходимо отклонять входящие сообщения
 * после достижения некоторых предельных размеров очереди. Работая на пределе длины очереди, прокся будет давать
 * постоянную латентность, пропорциональную длине очереди. Поэтому более умная реализация прокси, наверное, умела
 * бы в нужные моменты реджектить все сообщения в очереди. Этим самым мы бы отменили некоторые более старые сообщения
 * в пользу более новых и сократили бы латентность.
 *
 * При отклонении сообщения генерируется исключение {@link MessageRejectedException}.
 *
 * @author rushan
 */
public class AsyncProxy {

    private static final Logger log = LoggerFactory.getLogger(AsyncProxy.class);

    private final BlockingQueue<MessageTask> messageQueue;

    private static class MessageTask {
        private final Message message;
        private final CompletableFuture<ServiceResponse> future;

        private MessageTask(Message message, CompletableFuture<ServiceResponse> future) {
            this.message = message;
            this.future = future;
        }

        public Message getMessage() {
            return message;
        }

        public CompletableFuture<ServiceResponse> getFuture() {
            return future;
        }
    }

    private final int batchSize;
    private final int messageQueueLimit;

    private final ServiceRetry serviceRetry;
    private final List<Thread> ioThreads;

    public AsyncProxy(ServiceRetry serviceRetry, int ioThreads, int messageQueueLimit, int batchSize) {
        this.serviceRetry = serviceRetry;
        this.batchSize = batchSize;
        this.messageQueueLimit = messageQueueLimit;

        this.messageQueue = new LinkedBlockingQueue<>(messageQueueLimit);

        this.ioThreads = new ArrayList<>(ioThreads);
        for (int i = 0; i < ioThreads; i++) {
            Thread ioThread = new Thread(this::executeInIoThread);
            this.ioThreads.add(ioThread);
        }

        for (Thread ioThread : this.ioThreads) {
            ioThread.start();
        }

        log.info("Async Proxy: ioThreads = {}, batchSize = {}", ioThreads, batchSize);
    }

    public synchronized void dispose() throws InterruptedException {
        for (Thread ioThread : this.ioThreads) {
            ioThread.interrupt();
        }

        // Ждем, пока потоки не завершатся, обработав признак interrupt, установленный выше, или пока поток,
        // из которого был вызван dispose, не попросят остановиться
        for (Thread ioThread : this.ioThreads) {
            ioThread.join();
        }
    }

    private void executeInIoThread() {
        while (!Thread.currentThread().isInterrupted()) {
            List<MessageTask> tasks = new ArrayList<>();
            MessageTask firstTask;
            try {
                // блокируемся в синхронном ожидании для извлечения первой таски
                firstTask = messageQueue.take();
            } catch (InterruptedException e) {
                // Если попросили остановиться во время блокирующего ожидания - останавливаемся
                // через восстановление статуса interrupt и проверки в условии - чтобы была одна точка выхода из потока
                Thread.currentThread().interrupt();
                continue;
            }
            tasks.add(firstTask);
            // неблокирующим образом вытаскиваем остальные имеющиеся таски количеством не более batchSize - 1
            messageQueue.drainTo(tasks, this.batchSize - 1);

            List<Message> batch = tasks.stream().map(MessageTask::getMessage).collect(Collectors.toList());
            List<ServiceResponse> batchResult;
            try {
                batchResult = this.serviceRetry.call(batch);
                // по контракту call размер batchResult совпадает с batch

            } catch (Exception e) {
                for (MessageTask task : tasks) {
                    // Завершаем футуру ошибкой
                    task.getFuture().completeExceptionally(e);
                }
                continue;
            }

            for (int i = 0; i < batchResult.size(); i++) {
                MessageTask task = tasks.get(i);
                ServiceResponse response = batchResult.get(i);
                // Завершаем футуру результатом из батч-ответа
                task.getFuture().complete(response);
            }
        }

        log.info("The io thread is interrupted");
    }

    /**
     * Отправить асинхронно сообщение проксируемому сервису.
     *
     * @param message сообщение, которое должно быть отправлено проксируемому сервису
     * @return будущий результат с ответом сервиса
     * @throws MessageRejectedException если сообщение отклонено из-за превышения размеров очереди
     */
    public CompletableFuture<ServiceResponse> message(Message message) throws MessageRejectedException {

        MessageTask task = new MessageTask(message, new CompletableFuture<>());
        if (!this.messageQueue.offer(task)) {
            // Не будем обрабатывать сообщения, превышающие лимит размера очереди, чтобы предотвратить рост латентности
            throw new MessageRejectedException("The message is rejected - the message queue is reach the limit "
                    + this.messageQueueLimit);
        }
        return task.getFuture();
    }
}
