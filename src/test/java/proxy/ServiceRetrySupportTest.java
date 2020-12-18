package proxy;

import org.junit.Assert;
import org.junit.Test;
import proxy.api.ServiceCall;
import proxy.api.ServiceNotAvailableException;
import proxy.impl.ServiceRetrySupport;
import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * @author rushan
 */
public class ServiceRetrySupportTest {

    public static final byte[] TEST_DATA = "Test".getBytes();

    /**
     * Класс эквивалентности: успешный вызов при установленном пороге
     */
    @Test
    public void testSuccess() throws IOException, ServiceNotAvailableException {
        ServiceCall call = echoCall();
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 2, 10, 1000);

        Message message = new Message(Instant.now(), TEST_DATA);
        List<ServiceResponse> responses = invocationSupport.call(Collections.singletonList(message));
        Assert.assertEquals(1, responses.size());
        Assert.assertEquals("TestTest", new String(responses.get(0).getData()));
    }

    /**
     * Граничное условие - ошибка при первом вызове при установленном пороге
     */
    @Test
    public void testOneFailBeforeThreshold() throws IOException, ServiceNotAvailableException {
        ServiceCall call = new ServiceCall() {
            byte count;

            @Override
            public ServiceResponse call(byte[] data) throws IOException {
                // При первом вызове будет ошибка
                if (count++ == 0) {
                    throw new IOException("Test error");
                }
                // При остальных вызовах - успех, возвращаем count
                return callWithResponse(new byte[] {count}).call(data);
            }
        };

        int timeBetweenCallMs = 5000;
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 3, timeBetweenCallMs, 10000);
        Message message = new Message(Instant.now(), TEST_DATA);

        Instant beforeCall = Instant.now();
        List<ServiceResponse> responses = invocationSupport.call(Collections.singletonList(message));
        // Проверим, что нет таймаута
        Assert.assertTrue(Duration.between(beforeCall, Instant.now()).toMillis() < 10);
        Assert.assertEquals(1, responses.size());
        Assert.assertArrayEquals(new byte[] {2}, responses.get(0).getData());
    }

    /**
     * Класс эквивалентности - ошибка до конца порога
     */
    @Test
    public void testFailToThresholdMiddle() throws IOException, ServiceNotAvailableException {
        ServiceCall call = new ServiceCall() {
            byte count;

            @Override
            public ServiceResponse call(byte[] data) throws IOException {
                if (++count < 3) {
                    throw new IOException("Test error");
                }
                // При остальных вызовах - успех, возвращаем count
                return callWithResponse(new byte[] {count}).call(data);
            }
        };

        int timeBetweenCallMs = 5000;
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 3, timeBetweenCallMs, 10000);
        Message message = new Message(Instant.now(), TEST_DATA);

        Instant beforeCall = Instant.now();
        List<ServiceResponse> responses = invocationSupport.call(Collections.singletonList(message));
        // Проверим, что нет таймаута
        Assert.assertTrue(Duration.between(beforeCall, Instant.now()).toMillis() < 10);
        Assert.assertEquals(1, responses.size());
        Assert.assertArrayEquals(new byte[] {3}, responses.get(0).getData());
    }

    /**
     * Граничное условие - ошибки вплоть до конца порога
     */
    @Test
    public void testOnThresholdEnd() throws IOException, ServiceNotAvailableException {
        ServiceCall call = new ServiceCall() {
            byte count;

            @Override
            public ServiceResponse call(byte[] data) throws IOException {
                if (++count < 4) {
                    throw new IOException("Test error");
                }
                // При остальных вызовах - успех, возвращаем count
                return callWithResponse(new byte[] {count}).call(data);
            }
        };

        int timeBetweenCallMs = 1000;
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 3, timeBetweenCallMs, 10000);
        Message message = new Message(Instant.now(), TEST_DATA);

        Instant beforeCall = Instant.now();
        List<ServiceResponse> responses = invocationSupport.call(Collections.singletonList(message));
        // Проверим, что нет таймаута
        long duration = Duration.between(beforeCall, Instant.now()).toMillis();
        Assert.assertTrue(duration > timeBetweenCallMs && duration < 1100);
        Assert.assertEquals(1, responses.size());
        Assert.assertArrayEquals(new byte[] {4}, responses.get(0).getData());
    }

    /**
     * Класс эквивалентности - ошибки за порогом
     */
    @Test
    public void testFailAfterThresholdEnd() throws IOException, ServiceNotAvailableException {
        ServiceCall call = new ServiceCall() {
            byte count;

            @Override
            public ServiceResponse call(byte[] data) throws IOException {
                if (++count < 5) {
                    throw new IOException("Test error");
                }
                // При остальных вызовах - успех, возвращаем count
                return callWithResponse(new byte[] {count}).call(data);
            }
        };

        int timeBetweenCallMs = 1000;
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 3, timeBetweenCallMs, 10000);
        Message message = new Message(Instant.now(), TEST_DATA);

        Instant beforeCall = Instant.now();
        List<ServiceResponse> responses = invocationSupport.call(Collections.singletonList(message));
        // Проверим, что нет таймаута
        long duration = Duration.between(beforeCall, Instant.now()).toMillis();
        Assert.assertTrue(duration > 2000 && duration < 2100);
        Assert.assertEquals(1, responses.size());
        Assert.assertArrayEquals(new byte[] {5}, responses.get(0).getData());
    }

    /**
     * Класс эквивалентности - не получен ответ в течение заданного времени, нет таймаута
     */
    @Test
    public void testServiceNotAvailableWithoutTimeout() {
        ServiceCall call = data -> {
            throw new IOException("Test error");
        };

        int serviceWaitTimeMs = 700;
        ServiceRetrySupport invocationSupport = new ServiceRetrySupport(call, 0, 200, serviceWaitTimeMs);
        Message message = new Message(Instant.now(), TEST_DATA);

        Instant beforeCall = Instant.now();
        try {
            invocationSupport.call(Collections.singletonList(message));
            Assert.fail("Service not available exception is expected");
        } catch (Exception e) {
            Instant afterCall = Instant.now();
            Assert.assertEquals(ServiceNotAvailableException.class, e.getClass());
            ServiceNotAvailableException ne = (ServiceNotAvailableException) e;
            Assert.assertEquals(4, ne.getRetryCount());
            long duration = Duration.between(beforeCall, afterCall).toMillis();
            Assert.assertTrue(duration > 800 && duration < 850);
        }
    }

    private ServiceCall echoCall() {
        return data -> {
            ByteBuffer inputBuff = ByteBuffer.wrap(data);
            inputBuff.getLong();
            int len = inputBuff.getInt();
            byte[] inputBytes = new byte[len];
            inputBuff.get(inputBytes);

            String str = new String(inputBytes);
            byte[] result = (str + str).getBytes();

            ByteBuffer outputBuff = ByteBuffer.allocate(4 + result.length);
            outputBuff.putInt(result.length);
            outputBuff.put(result);
            return ServiceResponse.ok(outputBuff.array());
        };
    }

    private ServiceCall callWithResponse(byte[] response) {
        return data -> {
            ByteBuffer outputBuff = ByteBuffer.allocate(4 + response.length);
            outputBuff.putInt(response.length);
            outputBuff.put(response);
            return ServiceResponse.ok(outputBuff.array());
        };
    }
}
