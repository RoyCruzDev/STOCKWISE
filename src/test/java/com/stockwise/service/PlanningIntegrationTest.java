package com.stockwise.service;

import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.StockReconstructor;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.prediction.DemandPattern;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.recommendation.ProductDecision;
import com.stockwise.recommendation.ScenarioSimulator;
import com.stockwise.repository.DecisionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flujo completo de planificación avanzada sobre el archivo de demostración (24 meses, 16 productos, 3 sucursales). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanningIntegrationTest {

    @TempDir
    static Path dir;
    AppContext ctx;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("plan.db"));
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 24, LocalDate.now().withDayOfMonth(1).minusDays(1));
        List<SheetData> hojas = ExcelReader.leer(file);
        for (ImportType tipo : ImportType.values()) {
            SheetData h = hojas.stream().filter(s -> ImportType.detectar(s.nombre()) == tipo).findFirst().orElseThrow();
            ctx.importacion().importar(ctx.importacion().previsualizar(h, tipo,
                    ctx.importacion().mapeoSugerido(tipo, h.encabezados())));
        }
    }

    @Test
    @Order(1)
    void unaDecisionPorProductoConModelosEspecializados() {
        PlanningService.Analisis a = ctx.planificacion().analizar();
        assertEquals(16, a.decisiones().size());
        assertEquals(16, a.decisiones().stream().map(ProductDecision::productoId).distinct().count());
        ProductDecision sil = a.decisiones().stream().filter(d -> d.metrics().producto().codigo().equals("SIL-001"))
                .findFirst().orElseThrow();
        assertEquals(DecisionEstrategica.DATOS_INSUFICIENTES, sil.decision());
        Set<DecisionEstrategica> tipos = a.decisiones().stream().map(ProductDecision::decision).collect(Collectors.toSet());
        assertTrue(tipos.size() >= 4, "Datos variados deben producir decisiones variadas: " + tipos);
        // Clasificación ADI/CV² y modelos especializados evaluados con 24 meses de historia.
        List<ProductMetrics> ms = a.snapshot().productos();
        assertTrue(ms.stream().filter(m -> m.forecast().patron() != DemandPattern.SIN_CLASIFICAR).count() >= 14);
        assertTrue(ms.stream().anyMatch(m -> m.forecast().metodos().stream().anyMatch(r ->
                r.metodo().startsWith("LightGBM") || r.metodo().startsWith("CatBoost") || r.metodo().equals("Croston-TSB"))));
        // Las órdenes pendientes del archivo demo se descuentan como tránsito.
        assertTrue(a.snapshot().suministro().values().stream().anyMatch(s -> s.enTransito() > 0));
        // ROI del plan disponible solo con inversión aprobada.
        PlanningService.Roi roi = PlanningService.roi(a.plan(), ms);
        if (roi.inversion() > 0) {
            assertNotNull(roi.roiEsperado());
        }
    }

    @Test
    @Order(2)
    void guardarDecisionesNoDuplicaYPermiteMarcarlas() {
        ctx.planificacion().generarYGuardar();
        ctx.planificacion().generarYGuardar();
        List<DecisionRepository.DecisionGuardada> g = ctx.planificacion().decisionesGuardadas();
        assertEquals(16, g.size());
        long id = g.get(0).id();
        ctx.planificacion().marcarDecision(id, true);
        assertEquals(DecisionRepository.APLICADA, ctx.planificacion().decisionesGuardadas().stream()
                .filter(d -> d.id() == id).findFirst().orElseThrow().estado());
        assertThrows(ValidationException.class, () -> ctx.planificacion().marcarDecision(id, false));
        // Cada análisis conserva sus resultados: el nuevo tiene sus 16 y la decisión aplicada sigue en el anterior.
        ctx.planificacion().generarYGuardar();
        assertEquals(16, ctx.planificacion().decisionesGuardadas().size());
        assertTrue(ctx.planificacion().decisionesGuardadas().stream().noneMatch(d -> d.id() == id));
        assertTrue(ctx.resultados().analisis().size() >= 3);
    }

    @Test
    @Order(3)
    void impactoQuedaPendienteSinDatosPosteriores() {
        PlanningService.EvaluacionImpacto ev = ctx.planificacion().evaluarImpacto();
        assertTrue(ev.impactos().isEmpty());
        assertTrue(ev.mensaje().startsWith("Pendiente"));
    }

    @Test
    @Order(4)
    void reconstruyeElStockHistoricoCoherente() {
        StockHistoryService.ResultadoReconstruccion r = ctx.stockHistorico().reconstruirYGuardar();
        assertTrue(r.combinaciones() >= 45);
        assertEquals(0, r.inconsistentes(), "Las compras demo reponen lo vendido: no debe haber stock negativo");
        assertTrue(r.dias() > 10_000);
        long lap = ctx.productos().listar().stream().filter(p -> p.codigo().equals("LAP-001")).findFirst()
                .orElseThrow().id();
        List<StockReconstructor.DiaStock> guardado = ctx.stockHistorico().guardado(lap, null);
        List<StockReconstructor.DiaStock> calculado = ctx.stockHistorico().reconstruir(lap, null);
        assertEquals(calculado.size(), guardado.size());
        assertEquals(calculado.get(0).stock(), guardado.get(0).stock(), 1e-6);
        assertEquals(ctx.inventario().porProducto(lap).stream().mapToInt(i -> i.stock()).sum(),
                calculado.get(calculado.size() - 1).stock(), 1e-6);
        LocalDate corte = ctx.analysis().fechaCorte();
        Map<Long, StockReconstructor.Resumen> res = ctx.stockHistorico().resumenPorProducto(corte.minusDays(89), corte);
        assertTrue(res.values().stream().filter(x -> x.disponibilidad() != null)
                .allMatch(x -> x.disponibilidad() >= 0 && x.disponibilidad() <= 1));
    }

    @Test
    @Order(5)
    void backtestingWalkForwardYScorecard() {
        BacktestService.Resultado bt = ctx.backtest().ejecutar(6, 1);
        assertTrue(bt.disponible());
        assertTrue(bt.productos().size() >= 14);
        assertNotNull(bt.wape());
        assertNotNull(bt.fva());
        assertFalse(bt.porPatron().isEmpty());
        assertTrue(bt.sinDatos().stream().anyMatch(s -> s.startsWith("SIL-001")));
        assertTrue(bt.productos().stream().allMatch(p -> p.puntos() == 6));
        assertThrows(ValidationException.class, () -> ctx.backtest().ejecutar(0, 1));

        ScorecardService.Scorecard sc = ctx.scorecard().calcular();
        assertNotNull(sc.puntaje());
        assertTrue(sc.puntaje() >= 0 && sc.puntaje() <= 100);
        assertEquals(6, sc.componentes().size());
        assertTrue(sc.componentes().stream().allMatch(c -> c.puntaje() != null), "Con datos demo todo es calculable");
    }

    @Test
    @Order(6)
    void simulacionWhatIfNoModificaDatos() {
        long compras = ctx.compras().recientes(100_000).size();
        ScenarioSimulator.Resultado r = ctx.planificacion().simular(new ScenarioSimulator.Escenario(0.3, null, 5, null));
        assertTrue(r.simulado().inversionNecesaria() >= r.base().inversionNecesaria());
        assertEquals(compras, ctx.compras().recientes(100_000).size());
    }
}
