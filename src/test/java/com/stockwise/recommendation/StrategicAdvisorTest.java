package com.stockwise.recommendation;

import com.stockwise.TestData;
import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.InventarioItem;
import com.stockwise.model.Producto;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoRecomendacion;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategicAdvisorTest {

    private final Sucursal lima = new Sucursal(1, "LIM", "Lima", "", "", true);
    private final Sucursal cusco = new Sucursal(2, "CUS", "Cusco", "", "", true);

    private InventarioItem inv(Producto p, Sucursal s, int stock) {
        return new InventarioItem(0, p.id(), p.codigo(), p.nombre(), s.id(), s.codigo(), s.nombre(), stock,
                p.costo(), p.precioVenta(), p.stockMinimo(), p.stockMaximo(), "");
    }

    private AnalysisSnapshot snapshot(List<ProductMetrics> ms, List<InventarioItem> inv, Map<String, Double> u90) {
        return new AnalysisSnapshot(TestData.CORTE, TestData.FIN, TestData.settings(), ms, List.of(lima, cusco), inv,
                u90, List.of());
    }

    @Test
    void recomiendaTrasladoDeSucursalConExcesoASucursalConFaltante() {
        Producto p = TestData.producto(1, "P1", 100, 60, 5, 0, 15);
        // Lima vende 30 u/mes y tiene 2 u; Cusco no vende y tiene 80 u.
        ProductMetrics m = TestData.metrics(p, 82, 30, 12);
        Map<String, Double> u90 = new HashMap<>();
        u90.put(AnalysisSnapshot.key(1, lima.id()), 90.0);
        AnalysisSnapshot s = snapshot(List.of(m), List.of(inv(p, lima, 2), inv(p, cusco, 80)), u90);

        List<TransferRule.Traslado> t = new TransferRule().calcular(s);
        assertEquals(1, t.size());
        assertEquals("CUS", t.get(0).origen().codigo());
        assertEquals("LIM", t.get(0).destino().codigo());
        // objetivo Lima = ceil(30 * (15/30.4 + 1)) = 45 -> faltan 43; Cusco excede 80
        assertEquals(43, t.get(0).cantidad());

        List<Recomendacion> recs = new TransferRule().evaluar(s);
        assertEquals(TipoRecomendacion.TRASLADO, recs.get(0).tipo());
        assertEquals(43 * 60.0, recs.get(0).beneficio(), 1e-6);
        assertEquals(cusco.id(), recs.get(0).sucursalId());
        assertEquals(lima.id(), recs.get(0).sucursalDestinoId());
    }

    @Test
    void sinExcesoNoHayTraslado() {
        Producto p = TestData.producto(1, "P1", 100, 60, 5, 0, 15);
        ProductMetrics m = TestData.metrics(p, 4, 30, 12);
        Map<String, Double> u90 = new HashMap<>();
        u90.put(AnalysisSnapshot.key(1, lima.id()), 90.0);
        u90.put(AnalysisSnapshot.key(1, cusco.id()), 90.0);
        AnalysisSnapshot s = snapshot(List.of(m), List.of(inv(p, lima, 2), inv(p, cusco, 2)), u90);
        assertTrue(new TransferRule().calcular(s).isEmpty());
    }

    @Test
    void generaRecomendacionesDeLosDistintosTipos() {
        Producto agotado = TestData.producto(1, "AGO", 100, 60, 5, 0, 15);
        Producto bajoMargen = TestData.producto(2, "MAR", 100, 95, 5, 0, 15);
        Producto sinVentas = TestData.producto(3, "SIN", 100, 60, 5, 0, 15);
        Producto exceso = TestData.producto(4, "EXC", 100, 60, 5, 50, 15);
        List<ProductMetrics> ms = List.of(TestData.metrics(agotado, 0, 40, 12), TestData.metrics(bajoMargen, 30, 20, 12),
                TestData.metrics(sinVentas, 25, 0, 0), TestData.metrics(exceso, 400, 20, 12));
        List<Recomendacion> recs = new StrategicAdvisor().generar(snapshot(ms, List.of(), Map.of()));

        assertTrue(has(recs, TipoRecomendacion.PREVENIR_DESABASTECIMIENTO, "AGO"));
        assertTrue(has(recs, TipoRecomendacion.AJUSTAR_PRECIO, "MAR"));
        assertTrue(has(recs, TipoRecomendacion.LIQUIDAR, "SIN"));
        assertTrue(has(recs, TipoRecomendacion.PREVENIR_SOBREINVENTARIO, "EXC"));
        assertTrue(has(recs, TipoRecomendacion.PRODUCTO_RENTABLE, "AGO"));
        assertFalse(recs.isEmpty());
        // Ordenadas por prioridad descendente
        for (int i = 1; i < recs.size(); i++) {
            assertTrue(recs.get(i - 1).prioridad().peso() >= recs.get(i).prioridad().peso());
        }
        // Toda recomendación lleva problema, acción y justificación
        for (Recomendacion r : recs) {
            assertNotNull(r.problema());
            assertNotNull(r.accion());
            assertNotNull(r.justificacion());
        }
    }

    @Test
    void ajusteDePrecioCalculaBeneficio() {
        Producto p = TestData.producto(2, "MAR", 100, 95, 5, 0, 15);
        ProductMetrics m = TestData.metrics(p, 30, 20, 12);
        Recomendacion r = new PricingRule().evaluar(snapshot(List.of(m), List.of(), Map.of())).get(0);
        double nuevoPrecio = 95 / (1 - 0.15);
        assertEquals((nuevoPrecio - 100) * 240, r.beneficio(), 0.01);
    }

    private static boolean has(List<Recomendacion> recs, TipoRecomendacion tipo, String codigo) {
        return recs.stream().anyMatch(r -> r.tipo() == tipo && r.producto().startsWith(codigo));
    }
}
