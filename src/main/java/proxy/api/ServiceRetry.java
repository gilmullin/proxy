package proxy.api;

import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.io.IOException;
import java.util.List;

/**
 * @author rushan
 */
@FunctionalInterface
public interface ServiceRetry {
    List<ServiceResponse> call(List<Message> messages) throws IOException, ServiceNotAvailableException;
}
