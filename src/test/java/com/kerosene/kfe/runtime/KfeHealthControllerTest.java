package com.kerosene.kfe.runtime;

import com.kerosene.kfe.integration.KfeFinancialRailHealthAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeHealthControllerTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final KfeFinancialRailHealthAdapter railHealth = mock(KfeFinancialRailHealthAdapter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DataSource> dataSourceProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeFinancialRailHealthAdapter> railHealthProvider = mock(ObjectProvider.class);

    private KfeHealthController controller;

    @BeforeEach
    void setUp() {
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(railHealthProvider.getIfAvailable()).thenReturn(null);
        controller = new KfeHealthController(dataSourceProvider, railHealthProvider);
    }

    @Test
    void liveAlwaysReturnsUp() {
        var snapshot = controller.live();
        assertThat(snapshot.status()).isEqualTo("UP");
    }

    @Test
    void readyReturnsUpWhenDatabaseUpAndNoRails() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().dependencies()).containsKey("database");
    }

    @Test
    void readyReturnsDownWhenDatabaseDown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("DB down"));

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }

    @Test
    void readyReturnsDownWhenAllRailsDead() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        when(railHealthProvider.getIfAvailable()).thenReturn(railHealth);
        when(railHealth.custodyProvider()).thenReturn(
                new KfeFinancialRailHealthAdapter.ProviderStatus("lnd", false, "LndRestLightningClient"));
        when(railHealth.activeRailProviders()).thenReturn(Map.of());

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }

    @Test
    void readyReturnsUpWhenAtLeastOneRailLive() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        when(railHealthProvider.getIfAvailable()).thenReturn(railHealth);
        when(railHealth.custodyProvider()).thenReturn(
                new KfeFinancialRailHealthAdapter.ProviderStatus("lnd", true, "LndRestLightningClient"));
        when(railHealth.activeRailProviders()).thenReturn(Map.of());

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().dependencies()).containsKey("custody-lnd");
    }
}
