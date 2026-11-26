package com.stockwise.recommendation;

import com.stockwise.TestData;
import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioSimulatorTest {

    private AnalysisSnapshot snapshot() {
        List<ProductMetrics> ms = List.of(
                TestData.metrics(TestData.producto(1, "A", 10, 6, 0, 0, 15), 5, 30, 12),
                TestData.metrics(TestData.producto(2, "B", 20, 12, 0, 0, 20), 40, 25, 12),
                TestData.metrics(TestData.producto(3, "C", 5, 3, 0, 0, 10), 300, 10, 12));
        return new AnalysisSnapshot(TestData.CORTE, TestData.FIN, TestData.settings(), ms, List.of(), List.of(), Map.of(),
                List.of());
    }

    @Test
    void escenarioBaseNoProduceCambios() {
        ScenarioSimulator.Resultado r = ScenarioSimulator.simular(snapshot(), 5000, ScenarioSimulator.Escenario.base());
        assertTrue(r.cambios().isEmpty());
        assertEquals(r.base().inversionNecesaria(), r.simulado().inversionNecesaria(), 1e-9);
        assertEquals(r.base().decisiones(), r.simulado().decisiones());
    }

    @Test
    void masDemandaYMasTiempoDeEntregaAumentanLaNecesidadDeCompra() {
        AnalysisSnapshot s = snapshot();
        ScenarioSimulator.Resultado demanda = ScenarioSimulator.simular(s, 1_000_000,
                new ScenarioSimulator.Escenario(0.5, null, 0, null));
        assertTrue(demanda.simulado().inversionNecesaria() > demanda.base().inversionNecesaria());
        assertTrue(demanda.simulado().unidadesAComprar() > demanda.base().unidadesAComprar());
        assertTrue(!demanda.cambios().isEmpty());

        ScenarioSimulator.Resultado lt = ScenarioSimulator.simular(s, 1_000_000,
                new ScenarioSimulator.Escenario(0, null, 20, null));
        assertTrue(lt.simulado().inversionNecesaria() > lt.base().inversionNecesaria());
    }

    @Test
    void presupuestoCeroNoApruebaCompras() {
        ScenarioSimulator.Resultado r = ScenarioSimulator.simular(snapshot(), 5000,
                new ScenarioSimulator.Escenario(0, 0.0, 0, null));
        assertEquals(0, r.simulado().inversionAprobada(), 1e-9);
        assertEquals(0, r.simulado().productosAComprar());
        assertTrue(r.simulado().productosSinPresupuesto() > 0);
        assertTrue(r.base().inversionAprobada() > 0);
    }

    @Test
    void mayorNivelDeServicioNoReduceLaInversion() {
        ScenarioSimulator.Resultado r = ScenarioSimulator.simular(snapshot(), 1_000_000,
                new ScenarioSimulator.Escenario(0, null, 0, 0.99));
        assertTrue(r.simulado().inversionNecesaria() >= r.base().inversionNecesaria());
    }

    @Test
    void validaLosParametrosDelEscenario() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSimulator.Escenario(-1, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSimulator.Escenario(0, -5.0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSimulator.Escenario(0, null, 0, 1.5));
    }
}
