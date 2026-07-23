package com.kerosene.kfe.rail;

public interface LightningPaymentGateway {

    boolean isLive();

    String providerName();

    CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command);
}
