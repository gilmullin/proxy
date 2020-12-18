package proxy.impl;

import proxy.api.ServiceCall;
import proxy.model.ServiceResponse;
import proxy.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * @author rushan
 */
public class BlockingHttpCall implements ServiceCall {

    private final URL serviceUrl;
    private final int connectTimeout;
    private final int readTimeout;

    public BlockingHttpCall(String serviceUrl, int connectTimeout, int readTimeout) {
        try {
            this.serviceUrl = new URL(serviceUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Failed to parse provided url:" + serviceUrl);
        }
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public ServiceResponse call(byte[] data) throws IOException {

        HttpURLConnection httpCon = (HttpURLConnection) this.serviceUrl.openConnection();
        httpCon.setDoOutput(true);
        httpCon.setRequestMethod("POST");
        httpCon.setConnectTimeout(this.connectTimeout);
        httpCon.setReadTimeout(this.readTimeout);
        try (OutputStream os = httpCon.getOutputStream()) {
            os.write(data);
        }

        byte[] body = Util.readMessageBody(httpCon.getInputStream(), Integer.MAX_VALUE);
        int status = httpCon.getResponseCode();
        return ServiceResponse.response(status, body);
    }

    @Override
    public String toString() {
        return this.serviceUrl.toString();
    }
}
