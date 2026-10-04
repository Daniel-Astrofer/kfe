package com.kerosene.kfe.pricing.application.port.out;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;

public interface PricingPolicyPort {
    PricingPolicySnapshot snapshot(PaymentRail rail, PaymentDirection direction);
}
