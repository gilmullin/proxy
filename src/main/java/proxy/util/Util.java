package proxy.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rushan
 */
public class Util {

    public static final int BUFFER_SIZE = 2048;

    public static byte[] readMessageBody(InputStream messageStream, int maxLen) throws IOException {
        ByteArrayOutputStream memoryStream = new ByteArrayOutputStream(BUFFER_SIZE);
        long count = 0;
        int len;
        byte[] buf = new byte[BUFFER_SIZE];
        while ((len = messageStream.read(buf)) != -1) {
            memoryStream.write(buf, 0, len);
            count += len;

            if (count > maxLen) {
                throw new IllegalStateException("Too large stream. Max allowed size (bytes): " + maxLen);
            }
        }
        return memoryStream.toByteArray();
    }

    public static Map<String, String> readArguments(String[] args) {
        Map<String, String> params = new HashMap<>();
        String param = null;
        for (String arg : args) {
            if (arg.startsWith("-") && arg.length() > 1) {
                param = arg.substring(1);
            } else if (param != null) {
                params.put(param, arg);
            }
        }
        return params;
    }
}
