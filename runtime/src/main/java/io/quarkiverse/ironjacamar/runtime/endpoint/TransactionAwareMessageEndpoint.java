package io.quarkiverse.ironjacamar.runtime.endpoint;

import java.lang.reflect.Method;

import javax.transaction.xa.XAResource;

import jakarta.resource.ResourceException;
import jakarta.resource.spi.endpoint.MessageEndpoint;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;

import com.arjuna.ats.jta.TransactionManager;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;

public class TransactionAwareMessageEndpoint implements MessageEndpoint {

    private final XAResource xaResource;
    private final boolean transacted;

    public TransactionAwareMessageEndpoint(XAResource xaResource, boolean transacted) {
        this.xaResource = xaResource;
        this.transacted = transacted;
    }

    /**
     * Initiate a transaction and enlist the XA Resource only if @Transactional is present on the endpoint method
     */
    @Override
    public void beforeDelivery(Method method) throws ResourceException {
        if (transacted) {
            if (!QuarkusTransaction.isActive()) {
                Arc.container().requestContext().activate();
                QuarkusTransaction.begin();
            }
            if (xaResource != null) {
                try {
                    Transaction transaction = TransactionManager.transactionManager().getTransaction();
                    // Enlisting the resource so the message delivery is part of the transaction
                    // See https://jakarta.ee/specifications/connectors/2.1/jakarta-connectors-spec-2.1#transacted-delivery-using-container-managed-transaction
                    if (!transaction.enlistResource(xaResource)) {
                        throw new ResourceException("Cannot enlist resource");
                    }
                } catch (RollbackException | SystemException e) {
                    throw new ResourceException("Error while enlisting resource", e);
                }
            }
        }
    }

    @Override
    public void afterDelivery() throws ResourceException {
        if (transacted) {
            if (QuarkusTransaction.isActive()) {
                if (QuarkusTransaction.isRollbackOnly()) {
                    QuarkusTransaction.rollback();
                } else {
                    QuarkusTransaction.commit();
                }
            }
            Arc.container().requestContext().deactivate();
        }
    }

    @Override
    public void release() {

    }
}
