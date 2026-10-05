package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentProposalHashPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentProposal;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.springframework.stereotype.Component;

@Component
public class LegacyPaymentProposalHashAdapter implements PaymentProposalHashPort {
    private final KfeHashService hashes;

    public LegacyPaymentProposalHashAdapter(KfeHashService hashes) { this.hashes = hashes; }

    @Override
    public String hash(PaymentProposal proposal) { return hashes.sha256(proposal.canonicalContent()); }
}
