package uz.drenix.identity.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the two services this one reads from live. */
@ConfigurationProperties(prefix = "drenix.clients")
public class UpstreamProperties {

    private Endpoint userService = new Endpoint("user-service", 9090);
    private Endpoint performanceService = new Endpoint("performance-service", 9094);

    public static class Endpoint {
        private String host;
        private int port;

        public Endpoint() {
        }

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public Endpoint getUserService() { return userService; }
    public void setUserService(Endpoint userService) { this.userService = userService; }
    public Endpoint getPerformanceService() { return performanceService; }
    public void setPerformanceService(Endpoint p) { this.performanceService = p; }
}
