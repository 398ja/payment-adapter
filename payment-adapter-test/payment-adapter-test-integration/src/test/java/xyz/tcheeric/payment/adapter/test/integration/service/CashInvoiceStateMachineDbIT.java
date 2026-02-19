package xyz.tcheeric.payment.adapter.test.integration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;

@Import(CashInvoiceStateMachine.class)
class CashInvoiceStateMachineDbIT extends BasePostgresIT {

    @Autowired
    private CashInvoiceStateMachine stateMachine;

    @Autowired
    private CashInvoiceRepository invoiceRepository;

    @BeforeEach
    void cleanUp() {
        invoiceRepository.deleteAll();
    }

    // Verifies that CREATED -> PENDING transition persists correctly
    @Test
    void transition_createdToPending_persistsTimestamp() {
        CashInvoice invoice = TestDataFactory.createInvoice();
        invoiceRepository.save(invoice);

        stateMachine.transition(invoice, CashInvoiceStatus.PENDING);
        invoiceRepository.save(invoice);

        CashInvoice loaded = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);
        assertThat(loaded.getPublishedAt()).isNotNull();
    }

    // Verifies that PENDING -> INTENT_RECEIVED -> PAID chain persists all timestamps
    @Test
    void transition_fullPaymentFlow_persistsAllTimestamps() {
        CashInvoice invoice = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        invoiceRepository.save(invoice);

        stateMachine.transition(invoice, CashInvoiceStatus.INTENT_RECEIVED);
        invoiceRepository.save(invoice);

        stateMachine.transition(invoice, CashInvoiceStatus.PAID);
        invoiceRepository.save(invoice);

        CashInvoice loaded = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CashInvoiceStatus.PAID);
        assertThat(loaded.getIntentReceivedAt()).isNotNull();
        assertThat(loaded.getPaidAt()).isNotNull();
    }

    // Verifies that tryExpire on expired invoice persists EXPIRED status
    @Test
    void tryExpire_expiredInvoice_persistsExpiredStatus() {
        CashInvoice invoice = TestDataFactory.createExpiredInvoice();
        invoiceRepository.save(invoice);

        boolean expired = stateMachine.tryExpire(invoice);
        invoiceRepository.save(invoice);

        assertThat(expired).isTrue();

        CashInvoice loaded = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CashInvoiceStatus.EXPIRED);
    }

    // Verifies that tryExpire on non-expired invoice does not change status
    @Test
    void tryExpire_activeInvoice_doesNotChangeStatus() {
        CashInvoice invoice = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        invoiceRepository.save(invoice);

        boolean expired = stateMachine.tryExpire(invoice);

        assertThat(expired).isFalse();
        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);
    }
}
