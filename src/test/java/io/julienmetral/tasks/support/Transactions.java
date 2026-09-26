package io.julienmetral.tasks.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public final class Transactions {

    private Transactions() {
    }

    /**
     * Runs {@code work} in a transaction of its own, on another pooled connection, and commits it before returning,
     * while the caller's transaction stays open: what another instance of the application would do at that moment.
     */
    public static <T> T inNewTransaction(PlatformTransactionManager transactionManager, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }
}
