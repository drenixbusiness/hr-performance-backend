package uz.drenix.identity.performance.ai;

/** The model could not be reached, or no key is configured. */
public class AiUnavailableException extends uz.drenix.platform.security.UpstreamUnavailableException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
