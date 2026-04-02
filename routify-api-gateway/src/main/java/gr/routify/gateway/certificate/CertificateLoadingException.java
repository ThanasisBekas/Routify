package gr.routify.gateway.certificate;

/**
 * Thrown when a certificate cannot be loaded, parsed, or decrypted.
 */
public class CertificateLoadingException extends RuntimeException {

    public CertificateLoadingException(String message) {
        super(message);
    }

    public CertificateLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}

