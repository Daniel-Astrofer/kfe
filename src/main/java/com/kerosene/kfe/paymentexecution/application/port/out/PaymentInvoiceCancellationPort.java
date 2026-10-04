package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;

/**
 * Asks the invoice provider to cancel and reports whether it confirmed cancellation.
 * A provider-side cancellation cannot be undone by a local transaction rollback.
 */
public interface PaymentInvoiceCancellationPort {

    boolean cancel(CancelPaymentInvoiceCommand command);
}
