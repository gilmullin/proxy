package proxy.api;

import proxy.model.ServiceResponse;

import java.io.IOException;

/**
 * Синхронный вызов сервиса.
 */
@FunctionalInterface
public interface ServiceCall {
    ServiceResponse call(byte[] data) throws IOException;
}
