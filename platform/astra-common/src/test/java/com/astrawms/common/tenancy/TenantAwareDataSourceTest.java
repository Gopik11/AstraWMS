package com.astrawms.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.astrawms.common.security.AccessScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The app.owners value read by the owner_isolation policies (ADR-0030 C). */
class TenantAwareDataSourceTest {

    private static AccessScope owners(Set<String> owners) {
        return new AccessScope(null, owners, null, null);
    }

    @Test
    void unrestrictedScopesSeeEveryOwner() {
        assertThat(TenantAwareDataSource.owners(AccessScope.UNRESTRICTED)).isEqualTo("*");
        assertThat(TenantAwareDataSource.owners(null)).isEqualTo("*");
    }

    @Test
    void ownersAreListedSortedAndAnEmptyScopeSeesNone() {
        assertThat(TenantAwareDataSource.owners(owners(new LinkedHashSet<>(List.of("BETA", "ACME"))))).isEqualTo("ACME,BETA");
        assertThat(TenantAwareDataSource.owners(owners(Set.of()))).isEmpty();
    }

    @Test
    void anOwnerThatCannotBeExpressedIsLeftOutNeverWidened() {
        // a comma would split into two owners; "*" would mean every owner: both are dropped (fail closed)
        assertThat(TenantAwareDataSource.owners(owners(Set.of("A,B")))).isEmpty();
        assertThat(TenantAwareDataSource.owners(owners(Set.of("*")))).isEmpty();
        assertThat(TenantAwareDataSource.owners(owners(new LinkedHashSet<>(List.of("ACME", "*", "X,Y", ""))))).isEqualTo("ACME");
    }
}
