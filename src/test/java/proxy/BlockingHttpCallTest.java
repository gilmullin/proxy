package proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import proxy.impl.BlockingHttpCall;
import proxy.model.ServiceResponse;
import proxy.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * @author rushan
 */
public class BlockingHttpCallTest {

    public static final String TEST_REQUEST = "Test request";

    @Test
    public void testCall() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8090), 0);
        server.createContext("/sendMessage", exchange -> {
            byte[] bytes = Util.readMessageBody(exchange.getRequestBody(), Integer.MAX_VALUE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(null);
        server.start();

        BlockingHttpCall call = new BlockingHttpCall("http://localhost:8090/sendMessage", 100, 100);
        ServiceResponse echoResponse = call.call(TEST_REQUEST.getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(TEST_REQUEST, new String(echoResponse.getData(), StandardCharsets.UTF_8));
    }

    @Test(expected = SocketTimeoutException.class)
    public void testReadTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8091), 0);
        server.createContext("/sendMessage", exchange -> {

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }

            byte[] bytes = Util.readMessageBody(exchange.getRequestBody(), Integer.MAX_VALUE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(null);
        server.start();

        BlockingHttpCall call = new BlockingHttpCall("http://localhost:8091/sendMessage", 1000, 100);
        call.call(TEST_REQUEST.getBytes(StandardCharsets.UTF_8));
    }

    @Test(expected = ConnectException.class)
    public void testConnectException() throws IOException {
        // При отсутствии сервиса должен быть ConnectException

        BlockingHttpCall call = new BlockingHttpCall("http://localhost:8080/sendMessage", 1000, 100);
        call.call(TEST_REQUEST.getBytes(StandardCharsets.UTF_8));
    }

    @Test(expected = UnknownHostException.class)
    public void testDnsError() throws IOException {
        // Тестируем ошибку определения IP адреса
        BlockingHttpCall call = new BlockingHttpCall("http://baaljdaskdaace:8080/sendMessage", 1000, 100);
        call.call(TEST_REQUEST.getBytes(StandardCharsets.UTF_8));
    }
}
