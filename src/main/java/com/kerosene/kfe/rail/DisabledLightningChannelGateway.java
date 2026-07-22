package source.kfe.rail;

import java.util.List;

/**
 * Fail-closed channel gateway when LND REST is disabled.
 * Registered via {@link LightningChannelGatewayConfiguration} only when no other
 * {@link LightningChannelGateway} bean exists.
 */
public class DisabledLightningChannelGateway implements LightningChannelGateway {

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public String providerName() {
        return "DISABLED";
    }

    @Override
    public List<ChannelSnapshot> listChannels() {
        return List.of();
    }

    @Override
    public OpenChannelResult openChannel(OpenChannelCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }

    @Override
    public CloseChannelResult closeChannel(CloseChannelCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }

    @Override
    public UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }
}
