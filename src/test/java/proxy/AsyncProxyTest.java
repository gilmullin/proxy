package proxy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.MessageRejectedException;
import proxy.api.ServiceNotAvailableException;
import proxy.api.ServiceRetry;
import proxy.impl.AsyncProxy;
import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * @author rushan
 */
public class AsyncProxyTest {

    Logger log = LoggerFactory.getLogger(AsyncProxyTest.class);

    public static final byte[] TEST_DATA = "Test".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TEST_DATA1 = "Test1".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TEST_DATA2 = "Test2".getBytes(StandardCharsets.UTF_8);
    public static final byte[] TEST_DATA3 = "Test3".getBytes(StandardCharsets.UTF_8);
    private AsyncProxy proxy;

    @Before
    public void init() {

    }

    @Test
    public void testSingleMessage() throws MessageRejectedException {
        ServiceRetry serviceRetry = echo();

        proxy = new AsyncProxy(serviceRetry, 1, 100, 10);

        Message message = new Message(Instant.now(), TEST_DATA);
        ServiceResponse response = proxy.message(message).join();
        Assert.assertEquals(TEST_DATA, response.getData());
    }

    @Test
    public void testBatch() throws MessageRejectedException {

        ReentrantLock lock = new ReentrantLock();
        Condition inServiceCallCond = lock.newCondition();
        Condition batchReadyCond = lock.newCondition();
        boolean[] inCall = new boolean[1];
        boolean[] batchReady = new boolean[1];

        List<List<Message>> callInputs = new ArrayList<>();

        ServiceRetry serviceRetry = messages -> {
            lock.lock();
            try {
                callInputs.add(messages);
                inCall[0] = true;
                inServiceCallCond.signalAll();
                while (!batchReady[0]) {
                    log.debug("Service call - waiting for batch ready");
                    batchReadyCond.awaitUninterruptibly();
                }
                log.debug("Service call - batch ready");
                return echo().call(messages);
            } finally {
                lock.unlock();
            }
        };

        proxy = new AsyncProxy(serviceRetry, 1, 100, 10);

        Message message = new Message(Instant.now(), TEST_DATA);

        // Затравочное сообщение, чтобы вошло в service call и заблокировалось
        CompletableFuture<ServiceResponse> initialFut = proxy.message(message);

        CompletableFuture<ServiceResponse> fut1;
        CompletableFuture<ServiceResponse> fut2;
        CompletableFuture<ServiceResponse> lastMessageInQueueFuture;
        lock.lock();
        try {
            while (!inCall[0]) {
                log.debug("Prepare batch - waiting for service call");
                inServiceCallCond.awaitUninterruptibly();
            }

            log.debug("Prepare batch - service has called, start prepare batch");
            fut1 = proxy.message(new Message(Instant.now(), TEST_DATA1));
            fut2 = proxy.message(new Message(Instant.now(), TEST_DATA2));
            lastMessageInQueueFuture = proxy.message(new Message(Instant.now(), TEST_DATA3));

            batchReady[0] = true;
            batchReadyCond.signalAll();
        } finally {
            lock.unlock();
        }

        // Ждем обработки последнего сообщения в очереди. После обработки последнего сообщения - вся серия
        // должна быть обработана
        lastMessageInQueueFuture.join();
        Assert.assertTrue(initialFut.isDone());
        Assert.assertTrue(fut1.isDone());
        Assert.assertTrue(fut2.isDone());

        // Проверяем входные сообщения в сервис
        Assert.assertEquals(2, callInputs.size());
        List<Message> firstCallInput = callInputs.get(0);
        List<Message> secondCallInput = callInputs.get(1);
        // Размер первого батча равен одному
        Assert.assertEquals(1, firstCallInput.size());
        // Размер второго батча равен трем
        Assert.assertEquals(3, secondCallInput.size());

        Assert.assertArrayEquals(TEST_DATA, firstCallInput.get(0).getData());

        Assert.assertArrayEquals(TEST_DATA1, secondCallInput.get(0).getData());
        Assert.assertArrayEquals(TEST_DATA2, secondCallInput.get(1).getData());
        Assert.assertArrayEquals(TEST_DATA3, secondCallInput.get(2).getData());
    }

    @Test
    public void testServiceNotAvailable() {
        ServiceRetry serviceRetry = messages -> {
            throw new ServiceNotAvailableException(100, 3, null);
        };

        proxy = new AsyncProxy(serviceRetry, 1, 100, 10);

        Message message = new Message(Instant.now(), TEST_DATA);
        try {
            proxy.message(message).join();
        } catch (Exception e) {
            Assert.assertEquals(CompletionException.class, e.getClass());
            Assert.assertEquals(ServiceNotAvailableException.class, e.getCause().getClass());
        }
    }

    @Test
    public void testMessageRejected() throws MessageRejectedException, InterruptedException {
        CountDownLatch serviceCall = new CountDownLatch(1);
        ServiceRetry serviceRetry = messages -> {
            serviceCall.countDown();
            // Зависаем навсегда
            try {
                this.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted");
            }
            return null;
        };

        proxy = new AsyncProxy(serviceRetry, 1, 2, 10);

        Message message = new Message(Instant.now(), TEST_DATA);
        proxy.message(message);
        serviceCall.await();

        // Добавляем сообщения в очередь, в то время как единственный io-поток заблокирован в вызове сервиса
        proxy.message(new Message(Instant.now(), TEST_DATA1));
        proxy.message(new Message(Instant.now(), TEST_DATA1));
        // Так как допустимый размер очереди == 2, то на третьем ожидаем исключение определенного типа
        try {
            proxy.message(new Message(Instant.now(), TEST_DATA3));
        } catch (Exception e) {
            Assert.assertEquals(MessageRejectedException.class, e.getClass());
        }
    }

    private ServiceRetry echo() {
        return messages -> messages.stream().map(m -> ServiceResponse.ok(m.getData())).collect(Collectors.toList());
    }
}
