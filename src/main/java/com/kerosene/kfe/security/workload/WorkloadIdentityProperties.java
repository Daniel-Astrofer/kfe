package com.kerosene.kfe.security.workload;

import com.kerosene.common.security.workload.WorkloadIdentityConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("kerosene.workload-identity")
public class WorkloadIdentityProperties {

    private boolean enabled;
    private String socket = "";
    private String ownSpiffeId = "";
    private String peerSpiffeId = "";
    private int internalPort = 8443;
    private Duration initTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSocket() { return socket; }
    public void setSocket(String socket) { this.socket = socket; }
    public String getOwnSpiffeId() { return ownSpiffeId; }
    public void setOwnSpiffeId(String ownSpiffeId) { this.ownSpiffeId = ownSpiffeId; }
    public String getPeerSpiffeId() { return peerSpiffeId; }
    public void setPeerSpiffeId(String peerSpiffeId) { this.peerSpiffeId = peerSpiffeId; }
    public int getInternalPort() { return internalPort; }
    public void setInternalPort(int internalPort) { this.internalPort = internalPort; }
    public Duration getInitTimeout() { return initTimeout; }
    public void setInitTimeout(Duration initTimeout) { this.initTimeout = initTimeout; }

    public WorkloadIdentityConfig toConfig() {
        return new WorkloadIdentityConfig(
                enabled, socket, ownSpiffeId, peerSpiffeId, internalPort, initTimeout);
    }
}
