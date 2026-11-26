package com.stockwise.service;

import com.stockwise.database.Database;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.model.Producto;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corrida completa de análisis: avance real, cancelación, resultados, rankings, comparación y eliminación. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisRunTest {

    @TempDir
    static Path dir;
    AppContext ctx;
    LocalDate hasta;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("run.db"));
        hasta = LocalDate.of(2025, 6, 30);
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 24, hasta);
        List<SheetData> hojas = ExcelReader.leer(file);
        for (ImportType tipo : ImportType.values()) {
            SheetData h = hojas.stream().filter(s -> ImportType.detectar(s.nombre()) == tipo).findFirst().orElseThrow();
            ctx.importacion().importar(ctx.importacion().previsualizar(h, tipo,
                    ctx.importacion().mapeoSugerido(tipo, h.encabezados())), "demo.xlsx", h.encabezados());
        }
    }

    /** Registra el avance informado. */
    private static final class Registro implements Progreso {
        final List<Double> pct = new ArrayList<>();
        final List<String> msg = new ArrayList<>();
        final double cancelarEn;

        Registro(double cancelarEn) {
            this.cancelarEn = cancelarEn;
        }

        @Override
        public void avance(double porcentaje, String mensaje) {
            pct.add(porcentaje);
            msg.add(mensaje);
        }

        @Override
        public boolean cancelado() {
            return !pct.isEmpty() && pct.get(pct.size() - 1) >= cancelarEn;
        }
    }

    @Test
    @Order(1)
    void elAvanceEsRealMonotonoYRecorreTodasLasEtapas() {
        Registro r = new Registro(Double.MAX_VALUE);
        PlanningService.Analisis a = ctx.planificacion().analizar(r);
        for (int i = 1; i < r.pct.size(); i++) {
            assertTrue(r.pct.get(i) >= r.pct.get(i - 1), "El avance nunca retrocede");
        }
        assertEquals(1.0, r.pct.get(0));
        assertEquals(95.0, r.pct.get(r.pct.size() - 1));
        assertTrue(r.msg.contains("Validando información"));
        assertTrue(r.msg.contains("Calculando necesidades de compra"));
        assertTrue(r.msg.stream().anyMatch(m -> m.startsWith("Entrenando y evaluando modelos: 16 de 16")),
                "Avance por producto evaluado");
        // Muchos avances intermedios (uno por producto): el porcentaje refleja el trabajo hecho.
        assertTrue(r.pct.stream().filter(p -> p > 35 && p < 70).count() >= 10);
        assertEquals(16, a.detalles().size());
        assertNotNull(a.agregados().wape());
    }

    @Test
    @Order(2)
    void cancelarNoGuardaNada() {
        long antes = ctx.resultados().analisis().size();
        assertThrows(CancellationException.class, () -> ctx.planificacion().analizar(new Registro(40)));
        assertEquals(antes, ctx.resultados().analisis().size());
    }

    @Test
    @Order(3)
    void guardaResultadosConPronosticosMetricasYRankings() {
        long id = ctx.planificacion().guardar(ctx.planificacion().analizar(), 1234);
        List<DecisionGuardada> r = ctx.resultados().resultados(id);
        assertEquals(16, r.size());
        DecisionGuardada lap = r.stream().filter(x -> x.codigo().equals("LAP-001")).findFirst().orElseThrow();
        var d = lap.detalle();
        assertNotNull(d.p7());
        assertTrue(d.p7() <= d.p15() && d.p15() <= d.p30() && d.p30() <= d.p60() && d.p60() <= d.p90());
        assertNotNull(d.mae());
        assertNotNull(d.rmse());
        assertNotNull(d.wape());
        assertNotNull(d.sigmaMensual());
        assertEquals(24, ResultadosBuilder.leerNumeros(d.serieValores()).length);
        assertEquals(6, ResultadosBuilder.leerNumeros(d.pronosticoMensual()).length);
        var modelos = ResultadosBuilder.leerModelos(d.modelos());
        assertTrue(modelos.size() >= 5);
        assertEquals(1, modelos.stream().filter(ResultadosBuilder.ModeloComparado::seleccionado).count());
        assertTrue(modelos.stream().anyMatch(ResultadosBuilder.ModeloComparado::referencia));
        // Producto sin ventas: sin pronóstico inventado.
        DecisionGuardada sil = r.stream().filter(x -> x.codigo().equals("SIL-001")).findFirst().orElseThrow();
        assertNull(sil.detalle().p30());
        assertEquals("Sin ventas", sil.detalle().perfil());
        assertEquals(DecisionEstrategica.DATOS_INSUFICIENTES, sil.decision());

        var a = ctx.resultados().buscar(id).orElseThrow();
        assertEquals(1234L, a.duracionMs());
        ResultsService.Kpis k = ResultsService.kpis(a, r);
        assertEquals(16, k.productos());
        assertEquals(15, k.conPronostico());
        assertNotNull(k.mayorDemanda());

        List<DecisionGuardada> mas = ResultsService.ranking(r, ResultsService.Ranking.MAS_VENDIDOS, 90, 0);
        for (int i = 1; i < mas.size(); i++) {
            assertTrue(mas.get(i - 1).detalle().unidades365() >= mas.get(i).detalle().unidades365());
        }
        assertTrue(ResultsService.ranking(r, ResultsService.Ranking.PARA_RECOMPRAR, 90, 0).stream()
                .allMatch(x -> x.cantidadSugerida() > 0));
        assertTrue(ResultsService.ranking(r, ResultsService.Ranking.NO_RECOMPRAR, 90, 0).stream()
                .allMatch(x -> x.decision() == DecisionEstrategica.LIQUIDAR || x.decision() == DecisionEstrategica.REDUCIR
                        || x.decision() == DecisionEstrategica.DESCONTINUAR));
        assertTrue(ResultsService.ranking(r, ResultsService.Ranking.MAYOR_CRECIMIENTO, 90, 0).stream()
                .allMatch(x -> "CRECIENTE".equals(x.detalle().tendencia())));
        assertTrue(ResultsService.ranking(r, ResultsService.Ranking.OLVIDADOS, 90, 0).stream()
                .anyMatch(x -> x.codigo().equals("SIL-001")), "Nunca vendido y con stock");
        assertEquals(5, ResultsService.ranking(r, ResultsService.Ranking.MAS_VENDIDOS, 90, 5).size());
        // La compra recomendada no sale del ranking de ventas: también los menos vendidos pueden necesitar compra
        // y los más vendidos con exceso no se compran.
        assertEquals(ResultsService.pregunta(r, ResultsService.Pregunta.COMPRAR),
                ResultsService.ranking(r, ResultsService.Ranking.PARA_RECOMPRAR, 90, 0));
        assertTrue(ResultsService.pregunta(r, ResultsService.Pregunta.PERDIENDO_DEMANDA).stream()
                .allMatch(x -> "DECRECIENTE".equals(x.detalle().tendencia())));
    }

    @Test
    @Order(4)
    void compararDetectaLaPrecisionRealConMesesNuevos() {
        long a = ctx.resultados().analisis().get(0).id();
        long suc = ctx.sucursales().listar().get(0).id();
        // Llega julio: ventas nuevas de todos los productos que se venden.
        int ref = 0;
        for (Producto p : ctx.productos().listar()) {
            if (!p.codigo().equals("SIL-001")) {
                ctx.ventas().registrar(hasta.plusDays(10), p.id(), suc, 5, p.precioVenta(), "JUL-" + ref++, false);
                ctx.ventas().registrar(hasta.plusDays(31), p.id(), suc, 5, p.precioVenta(), "JUL-" + ref++, false);
            }
        }
        long b = ctx.planificacion().guardar(ctx.planificacion().analizar(), 10);
        ResultsService.Comparacion c = ctx.resultados().comparar(b, a);
        assertEquals(a, c.anterior().id(), "Se ordenan por fecha de corte");
        assertEquals(30, c.diasEvaluados());
        assertNotNull(c.wapeRealAnterior());
        assertEquals(16, c.cambios().size());
        assertTrue(c.cambios().stream().filter(x -> x.real() != null).allMatch(x -> x.real() == 5.0 || x.real() == 0.0));
        assertTrue(c.resumen().get(0).startsWith("Precisión real del análisis anterior"));
        // Misma fecha de corte: la precisión real queda pendiente.
        long b2 = ctx.planificacion().guardar(ctx.planificacion().analizar(), 10);
        ResultsService.Comparacion igual = ctx.resultados().comparar(b, b2);
        assertEquals(0, igual.diasEvaluados());
        assertNull(igual.wapeRealAnterior());
        assertTrue(igual.resumen().get(0).contains("pendiente"));
    }

    @Test
    @Order(5)
    void eliminarUnAnalisisNoBorraDatosHistoricos() {
        long ventas = ctx.ventaRepository().count();
        long compras = ctx.compras().recientes(100_000).size();
        int analisis = ctx.resultados().analisis().size();
        long id = ctx.resultados().analisis().get(0).id();
        ctx.resultados().eliminar(id);
        assertEquals(analisis - 1, ctx.resultados().analisis().size());
        assertTrue(ctx.resultados().resultados(id).isEmpty());
        assertEquals(ventas, ctx.ventaRepository().count());
        assertEquals(compras, ctx.compras().recientes(100_000).size());
        assertFalse(ctx.resultados().analisis().isEmpty(), "Los demás análisis se conservan");
        assertThrows(ValidationException.class, () -> ctx.resultados().eliminar(id));
    }

    @Test
    @Order(6)
    void sinVentasNoSeAnaliza() {
        AppContext vacio = new AppContext(dir.resolve("vacio.db"));
        assertThrows(ValidationException.class, () -> vacio.planificacion().analizar());
    }

    @Test
    @Order(7)
    void respaldaLaBaseAntesDeMigrarDeVersion() throws Exception {
        Path db = dir.resolve("vieja").resolve("stockwise.db");
        AppContext v = new AppContext(db);
        assertTrue(v.database() != null);
        // Simula una base de la versión anterior (sin número de versión del esquema).
        new Database(db).update("DELETE FROM configuracion WHERE clave = 'db.version'");
        new AppContext(db);
        try (var s = Files.list(db.getParent().resolve("backups"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("pre-migracion-v")));
        }
    }
}
