package com.stockwise.analytics;

import com.stockwise.TestData;
import com.stockwise.model.Producto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryAlertsTest {

    private final Producto p = TestData.producto(1, "P1", 100, 60, 10, 200, 15);

    @Test
    void stockCeroConDemandaEsAgotado() {
        ProductMetrics m = TestData.metrics(p, 0, 30, 12);
        assertEquals(EstadoInventario.AGOTADO, m.estado());
    }

    @Test
    void coberturaMenorAlTiempoDeReposicionEsRiesgo() {
        // 30 u/mes -> ~1 u/día; 12 u cubren ~12 días < 15 días de reposición
        ProductMetrics m = TestData.metrics(p, 12, 30, 12);
        assertEquals(EstadoInventario.RIESGO_AGOTAMIENTO, m.estado());
        assertTrue(m.coberturaDias() < 15);
    }

    @Test
    void stockBajoElMinimoEsRiesgoAunqueHayaCobertura() {
        Producto conMinAlto = TestData.producto(2, "P2", 100, 60, 50, 500, 15);
        ProductMetrics m = TestData.metrics(conMinAlto, 40, 10, 12);
        assertEquals(EstadoInventario.RIESGO_AGOTAMIENTO, m.estado());
    }

    @Test
    void stockSobreElMaximoEsSobreinventario() {
        ProductMetrics m = TestData.metrics(p, 250, 100, 12);
        assertEquals(EstadoInventario.SOBREINVENTARIO, m.estado());
    }

    @Test
    void coberturaMayorAlUmbralEsSobreinventario() {
        Producto sinMax = TestData.producto(3, "P3", 100, 60, 5, 0, 15);
        ProductMetrics m = TestData.metrics(sinMax, 200, 30, 12); // ~6.7 meses > 4
        assertEquals(EstadoInventario.SOBREINVENTARIO, m.estado());
    }

    @Test
    void coberturaAdecuadaEsNormal() {
        ProductMetrics m = TestData.metrics(p, 60, 30, 12); // 2 meses
        assertEquals(EstadoInventario.NORMAL, m.estado());
        assertEquals(60 / 30.0 * ProductMetrics.DIAS_POR_MES, m.coberturaDias(), 1.0);
    }

    @Test
    void sinVentasConStockEsSinMovimiento() {
        ProductMetrics m = TestData.metrics(p, 20, 0, 0);
        assertEquals(EstadoInventario.SIN_MOVIMIENTO, m.estado());
        assertFalse(m.demandaConfiable());
        assertNull(m.diasSinVenta());
        assertTrue(Double.isInfinite(m.coberturaDias()));
    }

    @Test
    void ventasAntiguasSonSinMovimiento() {
        MetricsCalculator.Params params = TestData.PARAMS;
        EstadoInventario e = MetricsCalculator.clasificar(50, 5, 0, 15, 10, 150, true, 120L, params);
        assertEquals(EstadoInventario.SIN_MOVIMIENTO, e);
    }

    @Test
    void usaValoresPorDefectoCuandoElProductoNoTieneParametros() {
        Producto sinParams = TestData.producto(4, "P4", 10, 5, 0, 0, null);
        ProductMetrics m = TestData.metrics(sinParams, 100, 10, 12);
        assertEquals(TestData.PARAMS.stockMinimoDefecto(), m.stockMinimo());
        assertEquals(TestData.PARAMS.leadTimeDefecto(), m.leadTimeDias());
    }
}
