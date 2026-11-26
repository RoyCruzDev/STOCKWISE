package com.stockwise.recommendation;

import com.stockwise.TestData;
import com.stockwise.analytics.MetricsCalculator;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Producto;
import com.stockwise.service.AppSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cada una de las 7 decisiones estratégicas (y el caso de datos insuficientes). */
class DecisionEngineTest {

    private final AppSettings cfg = TestData.settings(); // margen mínimo 15 %, sobreinventario 4 meses, 90 días
    private final DecisionEngine engine = new DecisionEngine();
    private final PurchaseRecommender recommender = new PurchaseRecommender(new PurchaseRecommender.Params(1, 0.95));

    private ProductDecision decidir(ProductMetrics m, SupplyInfo s, Double disponibilidad) {
        PurchaseRecommendation l = recommender.recomendar(List.of(m), 1_000_000, Map.of(m.productoId(), s))
                .lineas().get(0);
        return engine.evaluar(m, s, l, cfg, disponibilidad);
    }

    private ProductDecision decidir(ProductMetrics m) {
        return decidir(m, SupplyInfo.vacio(m.productoId()), null);
    }

    private static Producto prod(double precio, double costo) {
        return TestData.producto(1, "P1", precio, costo, 0, 0, 15);
    }

    @Test
    void recomprarCuandoLaPosicionLlegaAlPuntoDeReorden() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 5, 30, 12));
        assertEquals(DecisionEstrategica.RECOMPRAR, d.decision());
        assertTrue(d.cantidadSugerida() > 0);
        assertNotNull(d.impactoEstimado());
        assertNotNull(d.roiEsperado());
    }

    @Test
    void elTransitoEvitaRecomprarDeMas() {
        ProductMetrics m = TestData.metrics(prod(10, 6), 5, 30, 12);
        SupplyInfo transito = new SupplyInfo(1, null, null, 60, null, 0, "Producto", null);
        assertEquals(DecisionEstrategica.MANTENER, decidir(m, transito, null).decision());
    }

    @Test
    void mantenerConStockEquilibrado() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 45, 30, 12));
        assertEquals(DecisionEstrategica.MANTENER, d.decision());
        assertNull(d.impactoEstimado());
    }

    @Test
    void publicitarConBuenMargenYRotacionBaja() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 100, 30, 12));
        assertEquals(DecisionEstrategica.PUBLICITAR, d.decision());
        assertTrue(d.impactoEstimado() > 0);
    }

    @Test
    void reducirConSobreinventario() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 150, 30, 12));
        assertEquals(DecisionEstrategica.REDUCIR, d.decision());
    }

    @Test
    void liquidarConCoberturaMayorAlDobleDelUmbral() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 300, 30, 12));
        assertEquals(DecisionEstrategica.LIQUIDAR, d.decision());
        assertEquals((300 - 120) * 6.0, d.impactoEstimado(), 1e-9);
    }

    @Test
    void negociarConMargenInferiorAlMinimo() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 9.5), 5, 30, 12));
        assertEquals(DecisionEstrategica.NEGOCIAR, d.decision());
        assertTrue(d.accion().contains("Negociar"));
        assertTrue(d.cantidadSugerida() > 0, "Mantiene la necesidad de compra para el próximo pedido");
    }

    @Test
    void descontinuarSinVentasPorUnPeriodoProlongado() {
        Producto p = prod(10, 6);
        Map<String, Double> mensual = new HashMap<>();
        for (int i = 8; i < 20; i++) {
            mensual.put(TestData.FIN.minusMonths(i).toString(), 20.0);
        }
        MetricsCalculator.SalesData data = new MetricsCalculator.SalesData(mensual, 0, 0, 80, 800,
                TestData.CORTE.minusDays(200));
        ProductMetrics m = MetricsCalculator.calcular(p, 40, data, TestData.ENGINE, TestData.FIN, TestData.CORTE,
                TestData.PARAMS);
        ProductDecision d = decidir(m);
        assertEquals(DecisionEstrategica.DESCONTINUAR, d.decision());
        assertEquals(40 * 6.0, d.impactoEstimado(), 1e-9);
    }

    @Test
    void sinVentasRegistradasQuedaPendientePorDatosInsuficientes() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 12, 0, 0));
        assertEquals(DecisionEstrategica.DATOS_INSUFICIENTES, d.decision());
        assertNull(d.impactoEstimado());
        assertTrue(d.accion().startsWith("Pendiente"));
    }

    @Test
    void advierteDemandaSubestimadaPorQuiebresDeStock() {
        ProductDecision d = decidir(TestData.metrics(prod(10, 6), 5, 30, 12), SupplyInfo.vacio(1), 0.6);
        assertTrue(d.motivo().contains("subestimada"));
        assertEquals(0.6, d.disponibilidad());
    }
}
