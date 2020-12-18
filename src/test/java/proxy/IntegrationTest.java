package proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import proxy.model.ServiceResponse;
import proxy.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Тест, который тестирует все компоненты вместе (модуль Proxy целиком)
 *
 * @author rushan
 */
public class IntegrationTest {

    public static final byte[] TEST_DATA = "Test".getBytes();

    /**
     * Тестирует простой запуск и остановку с дефолтными параметрами
     */
    @Test
    public void testStartAndDisposeWithDefaultArguments() throws InterruptedException {
        Proxy proxy = new Proxy(new HashMap<>());
        proxy.start();
        proxy.dispose();
    }

    @Test
    public void testSingleMessage() throws InterruptedException, IOException {
        // Создаем тестовый сервер
        createAndStartTestService(8280);

        // Прокси
        HashMap<String, String> args = new HashMap<>();
        args.put("proxyPath", "/message");
        args.put("port", "8180");
        args.put("serviceUrl", "http://localhost:8280/sendMessage");
        Proxy proxy = new Proxy(args);
        proxy.start();

        // Делаем тестовый запрос
        ServiceResponse response = doResponseToProxy("http://localhost:8180/message", TEST_DATA);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals("TestTest".getBytes(), response.getData());

        proxy.dispose();
    }

    @Test
    public void testRealCase() throws InterruptedException, IOException {
        // Создаем тестовый сервер
        createAndStartTestService(8281);

        // Прокси
        HashMap<String, String> args = new HashMap<>();
        args.put("proxyPath", "/message");
        args.put("port", "8181");
        args.put("serviceUrl", "http://localhost:8281/sendMessage");
        args.put("minJettyThreads", "8");
        args.put("maxJettyThreads", "16");
        args.put("ioThreads", "12");
        Proxy proxy = new Proxy(args);
        proxy.start();

        String url = "http://localhost:8181/message";

        // Готовим и отправляем тестовые сообщения параллельно
        int requestCount = 1000;
        List<String> inputs = IntStream.range(0, requestCount).mapToObj(String::valueOf).sorted().collect(Collectors.toList());
        Map<String, ServiceResponse> responseMap = inputs.parallelStream()
                .map(r -> doResponseToProxy(url, r.getBytes()))
                .sorted(Comparator.comparing(resp -> new String(resp.getData())))
                .collect(Collectors.toMap(resp -> {
                    String dataStr = new String(resp.getData());
                    return dataStr.substring(0, dataStr.length() / 2);
                }, Function.identity()));

        for (String input : inputs) {
            ServiceResponse response = responseMap.get(input);
            Assert.assertEquals(200, response.getStatus());
            String expectedResp = input + input;
            Assert.assertEquals(expectedResp, new String(response.getData()));
        }

        proxy.dispose();
    }

    /**
     * Тестовый сервис, который задваивает вход
     */
    private void createAndStartTestService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/sendMessage", exchange -> {
            byte[] bytes = Util.readMessageBody(exchange.getRequestBody(), Integer.MAX_VALUE);
            ByteBuffer inputBuff = ByteBuffer.wrap(bytes);
            List<String> fromProxyList = new ArrayList<>();
            while (inputBuff.remaining() > 0) {
                inputBuff.getLong();
                int len = inputBuff.getInt();
                byte[] inputData = new byte[len];
                inputBuff.get(inputData);

                fromProxyList.add(new String(inputData));
            }

            int inputDataLen = fromProxyList.stream().mapToInt(String::length).sum();
            ByteBuffer outputBuf = ByteBuffer.allocate(4 * fromProxyList.size() + 2 * inputDataLen);

            for (String fromProxy : fromProxyList) {
                byte[] testResponse = (fromProxy + fromProxy).getBytes();
                outputBuf.putInt(testResponse.length);
                outputBuf.put(testResponse);
            }

            byte[] toRet = outputBuf.array();
            exchange.sendResponseHeaders(200, toRet.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(toRet);
            }
        });
        server.setExecutor(null);
        server.start();
    }

    private ServiceResponse doResponseToProxy(String url, byte[] data) {
        try {
            URL proxyUrl = new URL(url);
            HttpURLConnection httpCon = (HttpURLConnection) proxyUrl.openConnection();
            httpCon.setDoOutput(true);
            httpCon.setRequestMethod("POST");

            try (OutputStream os = httpCon.getOutputStream()) {
                os.write(data);
            }

            byte[] body = Util.readMessageBody(httpCon.getInputStream(), Integer.MAX_VALUE);
            int status = httpCon.getResponseCode();
            return ServiceResponse.response(status, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
