package com.stockwise.recommendation;

import com.stockwise.TestData;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Producto;
import com.stockwise.recommendation.PurchaseRecommendation.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseRecommenderTest {

    private final PurchaseRecommender recommender = new PurchaseRecommender(new PurchaseRecommender.Params(1, 0.95));

    private PurchaseRecommendation linea(PurchasePlan plan, String codigo) {
        return plan.lineas().stream().filter(l -> l.metrics().producto().codigo().equals(codigo)).findFirst()
                .orElseThrow();
    }

    @Test
    void recomiendaCompraCuandoElStockEstaBajoElPuntoDeReorden() {
        Producto p = TestData.producto(1, "A", 100, 50, 5, 0, 15);
        ProductMetrics m = TestData.metrics(p, 5, 60, 12); // 60 u/mes
        PurchasePlan plan = recommender.recomendar(List.of(m), 1_000_000);
        PurchaseRecommendation l = linea(plan, "A");
        assertEquals(Decision.COMPRAR, l.decision());
        // objetivo = 60 * (15/30.4 + 1) = ~89.6 (σ = 0) -> 90 - 5 = 85
        assertEquals(85, l.cantidadSugerida());
        assertEquals(85 * 50, l.costoTotal(), 1e-6);
        assertTrue(l.riesgoAgotamiento());
    }

    @Test
    void noRecomiendaCompraConStockSuficiente() {
        Producto p = TestData.producto(1, "A", 100, 50, 5, 0, 15);
        PurchasePlan plan = recommender.recomendar(List.of(TestData.metrics(p, 120, 30, 12)), 1_000_000);
        assertEquals(Decision.NO_REQUIERE, linea(plan, "A").decision());
        assertEquals(0, plan.totalAprobado());
        assertEquals(1, plan.noRequieren().size());
    }

    @Test
    void nuncaSuperaElPresupuesto() {
        Producto a = TestData.producto(1, "A", 100, 50, 5, 0, 15);
        Producto b = TestData.producto(2, "B", 200, 120, 5, 0, 15);
        Producto c = TestData.producto(3, "C", 30, 10, 5, 0, 15);
        List<ProductMetrics> ms = List.of(TestData.metrics(a, 0, 100, 12), TestData.metrics(b, 2, 80, 12),
                TestData.metrics(c, 1, 300, 12));
        for (double presupuesto : new double[]{0, 500, 3_333.33, 10_000, 25_000}) {
            PurchasePlan plan = recommender.recomendar(ms, presupuesto);
            assertTrue(plan.totalAprobado() <= presupuesto + 1e-9,
                    "Aprobado " + plan.totalAprobado() + " > presupuesto " + presupuesto);
            assertTrue(plan.saldo() >= -1e-9);
        }
    }

    @Test
    void presupuestoInsuficienteGeneraCompraParcialYPriorizaCriticos() {
        Producto agotado = TestData.producto(1, "AGOT", 100, 50, 5, 0, 15);
        Producto riesgo = TestData.producto(2, "MEDIO", 100, 50, 5, 0, 15);
        ProductMetrics m1 = TestData.metrics(agotado, 0, 60, 12);
        ProductMetrics m2 = TestData.metrics(riesgo, 25, 60, 12); // 25 u < punto de reorden (~29.6)
        PurchasePlan plan = recommender.recomendar(List.of(m2, m1), 3000);
        PurchaseRecommendation l1 = linea(plan, "AGOT");
        PurchaseRecommendation l2 = linea(plan, "MEDIO");
        assertEquals(Prioridad.CRITICA, l1.prioridad());
        assertEquals(Prioridad.ALTA, l2.prioridad());
        assertEquals(Decision.COMPRA_PARCIAL, l1.decision());
        assertEquals(60, l1.cantidadAprobada()); // 3000 / 50
        assertEquals(Decision.SIN_PRESUPUESTO, l2.decision());
        assertEquals(0, l2.cantidadAprobada());
        assertEquals(3000, plan.totalAprobado(), 1e-6);
    }

    @Test
    void sinPresupuestoNoApruebaNadaYLoAdvierte() {
        Producto p = TestData.producto(1, "A", 100, 50, 5, 0, 15);
        PurchasePlan plan = recommender.recomendar(List.of(TestData.metrics(p, 0, 60, 12)), 0);
        assertEquals(0, plan.totalAprobado());
        assertEquals(Decision.SIN_PRESUPUESTO, linea(plan, "A").decision());
        assertTrue(plan.notas().stream().anyMatch(n -> n.contains("No hay presupuesto")));
    }

    @Test
    void respetaElStockMaximo() {
        Producto p = TestData.producto(1, "A", 100, 50, 5, 40, 15);
        PurchasePlan plan = recommender.recomendar(List.of(TestData.metrics(p, 10, 60, 12)), 1_000_000);
        assertEquals(30, linea(plan, "A").cantidadSugerida()); // tope 40 - 10
    }

    @Test
    void productoSinVentasSoloReponeHastaElMinimo() {
        Producto p = TestData.producto(1, "A", 100, 50, 8, 0, 15);
        PurchasePlan plan = recommender.recomendar(List.of(TestData.metrics(p, 0, 0, 0)), 1_000_000);
        PurchaseRecommendation l = linea(plan, "A");
        assertEquals(8, l.cantidadSugerida());
        assertEquals(Prioridad.BAJA, l.prioridad());
    }
}
