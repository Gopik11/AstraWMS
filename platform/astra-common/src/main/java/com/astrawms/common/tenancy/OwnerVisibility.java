package com.astrawms.common.tenancy;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Lets a physical check see every owner's rows of the current tenant (ADR-0030 C).
 *
 * <p>The restrictive {@code owner_isolation} policies hide other owners' rows from an owner-scoped user. Some checks
 * must see them whoever the user acts for, because they are facts about the warehouse, not about one owner's stock:
 * whether a location already holds another owner's item, whether an LPN id is already in use, whether a slot is
 * empty. {@link #allOwners} runs such a check with {@code app.owners = *} for the duration of the check only,
 * then restores the user's owners. The tenant is unchanged: other tenants stay invisible.
 *
 * <p>Rules (checked by {@code scripts/check-architecture.sh}): every call carries an {@code all-owners: <reason>}
 * comment, and what it returns to the caller is a decision (yes/no, an id the user may see), never another owner's
 * rows. It needs a transaction, because {@code set_config(..., true)} lasts until the transaction ends and only a
 * transaction keeps one connection for the check and the restore.
 */
public final class OwnerVisibility {

    private OwnerVisibility() {
    }

    public static <T> T allOwners(JdbcClient jdbc, Supplier<T> check) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OwnerVisibility.allOwners needs a transaction");
        }
        String previous = jdbc.sql("select coalesce(current_setting('app.owners', true), '')")
                .query(String.class).single();
        jdbc.sql("select set_config('app.owners', :all, true)").param("all", TenantAwareDataSource.ALL_OWNERS)
                .query(String.class).single();
        try {
            return check.get();
        } finally {
            jdbc.sql("select set_config('app.owners', :previous, true)").param("previous", previous)
                    .query(String.class).single();
        }
    }
}
