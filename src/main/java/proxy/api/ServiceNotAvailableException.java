package proxy.api;

/**
 * Сервис не доступен.
 */
public class ServiceNotAvailableException extends Exception {

    private final long timeMs;
    private final int retryCount;

    public ServiceNotAvailableException(long timeMs, int retryCount, Throwable cause) {
        super("The service is not available in " + timeMs + "ms, retry count: " + retryCount, cause);
        this.timeMs = timeMs;
        this.retryCount = retryCount;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public int getRetryCount() {
        return retryCount;
    }
}
