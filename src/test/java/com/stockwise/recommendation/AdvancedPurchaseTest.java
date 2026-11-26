package com.stockwise.recommendation;

import com.stockwise.TestData;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.Statistics;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Producto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stock de seguridad con variabilidad del tiempo de entrega, stock en tránsito, ciclos de proveedor y ROI. */
class AdvancedPurchaseTest {

    private final PurchaseRecommender recommender = new PurchaseRecommender(new PurchaseRecommender.Params(1, 0.95));

    private static SupplyInfo suministro(long id, int transito, Integer ciclo, double sigmaLt) {
        return new SupplyInfo(id, null, "Proveedor X", transito, ciclo, sigmaLt, "Producto", null);
    }

    private PurchaseRecommendation linea(ProductMetrics m, SupplyInfo s, double presupuesto) {
        return recommender.recomendar(List.of(m), presupuesto, Map.of(m.productoId(), s)).lineas().get(0);
    }

    @Test
    void stockDeSeguridadIncluyeLaVariabilidadDelTiempoDeEntrega() {
        double z = Statistics.zScore(0.95);
        // Sin variabilidad del tiempo de entrega coincide con la fórmula clásica z·σ·√LT.
        assertEquals(z * 10 * Math.sqrt(0.5), PurchaseRecommender.stockSeguridad(z, 100, 10, 0.5, 0), 1e-9);
        // Con σLT: z·√(LT·σd² + d²·σLT²)
        assertEquals(z * Math.sqrt(0.5 * 100 + 100 * 100 * 0.1 * 0.1),
                PurchaseRecommender.stockSeguridad(z, 100, 10, 0.5, 0.1), 1e-9);
    }

    @Test
    void elStockEnTransitoSeDescuentaDeLaCompra() {
        // LT 30 días: ROP ≈ 29,6 u y nivel objetivo ≈ 59,6 u
        Producto p = TestData.producto(1, "A", 10, 6, 0, 0, 30);
        ProductMetrics m = TestData.metrics(p, 5, 30, 12);
        PurchaseRecommendation sin = linea(m, SupplyInfo.vacio(1), 100_000);
        PurchaseRecommendation con = linea(m, suministro(1, 20, null, 0), 100_000);
        assertEquals(20, con.enTransito());
        assertEquals(sin.cantidadSugerida() - 20, con.cantidadSugerida());
        assertEquals(25, con.posicionInventario());
        assertTrue(con.motivo().contains("en tránsito"));
        // Con suficiente tránsito ya no hace falta comprar.
        assertEquals(0, linea(m, suministro(1, 60, null, 0), 100_000).cantidadSugerida());
    }

    @Test
    void elCicloDelProveedorDefineElNivelObjetivo() {
        Producto p = TestData.producto(1, "A", 10, 6, 0, 0, 15);
        ProductMetrics m = TestData.metrics(p, 0, 30, 12);
        PurchaseRecommendation horizonte = linea(m, SupplyInfo.vacio(1), 100_000);
        PurchaseRecommendation quincenal = linea(m, suministro(1, 0, 15, 0), 100_000);
        double lt = 15 / ProductMetrics.DIAS_POR_MES;
        assertEquals(30 * (lt + 1), horizonte.nivelObjetivo(), 1e-6);
        assertEquals(30 * (lt + 15 / ProductMetrics.DIAS_POR_MES), quincenal.nivelObjetivo(), 1e-6);
        assertTrue(quincenal.cantidadSugerida() < horizonte.cantidadSugerida());
    }

    @Test
    void laVariabilidadDelProveedorElevaElPuntoDeReorden() {
        Producto p = TestData.producto(1, "A", 10, 6, 0, 0, 15);
        ProductMetrics m = TestData.metrics(p, 0, 30, 12);
        PurchaseRecommendation estable = linea(m, SupplyInfo.vacio(1), 100_000);
        PurchaseRecommendation variable = linea(m, suministro(1, 0, null, 6), 100_000);
        assertTrue(variable.stockSeguridad() > estable.stockSeguridad());
        assertTrue(variable.puntoReorden() > estable.puntoReorden());
    }

    @Test
    void roiEsperadoSoloConInversionYCosto() {
        Producto p = TestData.producto(1, "A", 10, 6, 0, 0, 15);
        PurchaseRecommendation l = linea(TestData.metrics(p, 0, 30, 12), SupplyInfo.vacio(1), 100_000);
        assertNotNull(l.roiEsperado());
        double vendibles = Math.min(l.cantidadAprobada(), l.demandaPeriodo());
        assertEquals(vendibles * 4 / (l.cantidadAprobada() * 6.0), l.roiEsperado(), 1e-9);
        // Sin presupuesto: no hay inversión ni ROI.
        assertNull(linea(TestData.metrics(p, 0, 30, 12), SupplyInfo.vacio(1), 0).roiEsperado());
        // Sin costo registrado: ROI no disponible (no se inventa).
        Producto sinCosto = TestData.producto(2, "B", 10, 0, 0, 0, 15);
        assertNull(linea(TestData.metrics(sinCosto, 0, 30, 12), SupplyInfo.vacio(2), 100_000).roiEsperado());
    }

    @Test
    void sinDatosDeAbastecimientoElPlanEsIdenticoAlOriginal() {
        Producto p = TestData.producto(1, "A", 10, 6, 0, 0, 15);
        ProductMetrics m = TestData.metrics(p, 3, 30, 12);
        PurchaseRecommendation a = recommender.recomendar(List.of(m), 1000).lineas().get(0);
        PurchaseRecommendation b = linea(m, SupplyInfo.vacio(1), 1000);
        assertEquals(a.cantidadSugerida(), b.cantidadSugerida());
        assertEquals(a.puntoReorden(), b.puntoReorden(), 1e-12);
        assertEquals(a.cantidadAprobada(), b.cantidadAprobada());
    }
}
