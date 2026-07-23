package source.kfe.rail;

public final class KfeRailException {

    private KfeRailException() {
    }

    public static class ProviderUnavailable extends RuntimeException {
        public ProviderUnavailable(String message) {
            super(message);
        }

        public ProviderUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
