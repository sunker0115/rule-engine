package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialStoreTest {

    private static FetchResourceProperties propsWith(String name, String value) {
        FetchResourceProperties props = new FetchResourceProperties();
        FetchResourceProperties.CredentialDef def = new FetchResourceProperties.CredentialDef();
        def.setName(name);
        def.setValue(value);
        props.setCredentials(List.of(def));
        return props;
    }

    @Test
    void get_returnsConfiguredValue() {
        CredentialStore store = new CredentialStore(propsWith("risk-cid", "abc"));
        assertThat(store.get("risk-cid")).isEqualTo("abc");
    }

    @Test
    void get_missingRef_returnsNull() {
        CredentialStore store = new CredentialStore(propsWith("risk-cid", "abc"));
        assertThat(store.get("ghost")).isNull();
    }

    @Test
    void get_emptyCredentials_returnsNull() {
        CredentialStore store = new CredentialStore(new FetchResourceProperties());
        assertThat(store.get("any")).isNull();
    }
}
