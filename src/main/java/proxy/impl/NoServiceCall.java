package proxy.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.api.ServiceCall;
import proxy.model.ServiceResponse;

/**
 * Stub, который только логгирует обращение.
 *
 * @author rushan
 */
public class NoServiceCall implements ServiceCall {

    private static final Logger log = LoggerFactory.getLogger(NoServiceCall.class);

    @Override
    public ServiceResponse call(byte[] data) {
        log.info("NoServiceCall: send buffer with length:  " + data.length);
        return ServiceResponse.ok(data);
    }
}
