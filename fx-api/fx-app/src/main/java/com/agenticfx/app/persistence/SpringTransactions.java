package com.agenticfx.app.persistence;

import com.agenticfx.core.port.Transactions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Core transaction port backed by Spring's TransactionTemplate (REQUIRED propagation, READ COMMITTED). */
@Component
public class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    public SpringTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
