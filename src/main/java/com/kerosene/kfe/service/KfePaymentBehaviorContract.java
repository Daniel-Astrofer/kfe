package com.kerosene.kfe.service;

/**
 * Service-level contract that documents and enforces payment request behavior.
 * Acts as a configuration snapshot per payment request — immutable after creation.
 *
 * <p>Each {@link KfePaymentRequestEntity} carries one instance of this contract,
 * serialized as a JSON column ({@code behavior_contract}) for auditability.
 */
public class KfePaymentBehaviorContract {

    public enum LinkType {
        /** First payment marks the link as PAID; subsequent payments rejected. */
        SINGLE_USE,
        /** Link stays OPEN after each payment; payer can reuse. */
        REUSABLE
    }

    public enum PartialPaymentPolicy {
        /** Accept partial payments and track cumulative received. */
        ACCEPT_AND_TRACK,
        /** Refuse any payment below the declared amount. */
        REJECT,
        /** Hold partial payment but do not credit until full amount arrives. */
        HOLD_FOR_FULL
    }

    public enum OverpaymentPolicy {
        /** Credit the full received amount (current behavior). */
        CREDIT_FULL,
        /** Credit declared amount, refund excess to sender. */
        REFUND_EXCESS,
        /** Credit declared amount, hold excess for manual review. */
        HOLD_EXCESS
    }

    public enum ExpiredPaymentPolicy {
        /** Refuse any payment after expiry. */
        REJECT,
        /** Accept if the invoice settled within a short grace period after expiry (monitor reconciliation path). */
        ACCEPT_WITHIN_GRACE_PERIOD,
        /** Always accept if the invoice is confirmed settled on the rail, regardless of KFE clock. */
        ALWAYS_ACCEPT_IF_CONFIRMED
    }

    private LinkType linkType;
    private PartialPaymentPolicy partialPaymentPolicy;
    private OverpaymentPolicy overpaymentPolicy;
    private ExpiredPaymentPolicy expiredPaymentPolicy;

    public KfePaymentBehaviorContract() {
        this.linkType = LinkType.SINGLE_USE;
        this.partialPaymentPolicy = PartialPaymentPolicy.REJECT;
        this.overpaymentPolicy = OverpaymentPolicy.CREDIT_FULL;
        this.expiredPaymentPolicy = ExpiredPaymentPolicy.REJECT;
    }

    public KfePaymentBehaviorContract(
            LinkType linkType,
            PartialPaymentPolicy partialPaymentPolicy,
            OverpaymentPolicy overpaymentPolicy,
            ExpiredPaymentPolicy expiredPaymentPolicy) {
        this.linkType = linkType != null ? linkType : LinkType.SINGLE_USE;
        this.partialPaymentPolicy = partialPaymentPolicy != null ? partialPaymentPolicy : PartialPaymentPolicy.REJECT;
        this.overpaymentPolicy = overpaymentPolicy != null ? overpaymentPolicy : OverpaymentPolicy.CREDIT_FULL;
        this.expiredPaymentPolicy = expiredPaymentPolicy != null ? expiredPaymentPolicy : ExpiredPaymentPolicy.REJECT;
    }

    /**
     * Default contract for fixed-amount payment links.
     * SINGLE_USE, REJECT partial, CREDIT_FULL overpayment, REJECT after expiry.
     */
    public static KfePaymentBehaviorContract forFixedAmount() {
        return new KfePaymentBehaviorContract(
                LinkType.SINGLE_USE, PartialPaymentPolicy.REJECT,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.REJECT);
    }

    /**
     * Default contract for open-amount payment links.
     * SINGLE_USE, ACCEPT_AND_TRACK partials, CREDIT_FULL overpayment, ACCEPT_WITHIN_GRACE_PERIOD for expiry.
     */
    public static KfePaymentBehaviorContract forOpenAmount() {
        return new KfePaymentBehaviorContract(
                LinkType.SINGLE_USE, PartialPaymentPolicy.ACCEPT_AND_TRACK,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.ACCEPT_WITHIN_GRACE_PERIOD);
    }

    /**
     * Default contract for reusable payment links (e.g., tip jars, donation pages).
     * REUSABLE, ACCEPT_AND_TRACK partials, CREDIT_FULL overpayment, ALWAYS_ACCEPT_IF_CONFIRMED for expiry.
     */
    public static KfePaymentBehaviorContract forReusable() {
        return new KfePaymentBehaviorContract(
                LinkType.REUSABLE, PartialPaymentPolicy.ACCEPT_AND_TRACK,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.ALWAYS_ACCEPT_IF_CONFIRMED);
    }

    // --- Getters / setters (for JSON serialization) ---

    public LinkType getLinkType() { return linkType; }
    public void setLinkType(LinkType linkType) { this.linkType = linkType; }

    public PartialPaymentPolicy getPartialPaymentPolicy() { return partialPaymentPolicy; }
    public void setPartialPaymentPolicy(PartialPaymentPolicy partialPaymentPolicy) { this.partialPaymentPolicy = partialPaymentPolicy; }

    public OverpaymentPolicy getOverpaymentPolicy() { return overpaymentPolicy; }
    public void setOverpaymentPolicy(OverpaymentPolicy overpaymentPolicy) { this.overpaymentPolicy = overpaymentPolicy; }

    public ExpiredPaymentPolicy getExpiredPaymentPolicy() { return expiredPaymentPolicy; }
    public void setExpiredPaymentPolicy(ExpiredPaymentPolicy expiredPaymentPolicy) { this.expiredPaymentPolicy = expiredPaymentPolicy; }

    public boolean isSingleUse() { return linkType == LinkType.SINGLE_USE; }
    public boolean isReusable() { return linkType == LinkType.REUSABLE; }
    public boolean rejectsPartial() { return partialPaymentPolicy == PartialPaymentPolicy.REJECT; }
    public boolean acceptsTrackedPartial() { return partialPaymentPolicy == PartialPaymentPolicy.ACCEPT_AND_TRACK; }
    public boolean creditsFullOverpayment() { return overpaymentPolicy == OverpaymentPolicy.CREDIT_FULL; }
    public boolean acceptsWithinGracePeriod() { return expiredPaymentPolicy == ExpiredPaymentPolicy.ACCEPT_WITHIN_GRACE_PERIOD; }
    public boolean alwaysAcceptsIfConfirmed() { return expiredPaymentPolicy == ExpiredPaymentPolicy.ALWAYS_ACCEPT_IF_CONFIRMED; }
}
