package com.stockwise.service;

import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.prediction.HorizonForecast;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.AnalisisRepository.Analisis;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.repository.DecisionRepository.DetalleAnalisis;
import com.stockwise.service.ResultsService.FuenteDatos;
import com.stockwise.service.ResultsService.Pregunta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pantalla de resultados orientada a decisiones: preguntas del gerente, explicaciones sencillas, fuentes de datos y
 * rangos/precisión por horizonte guardados con cada corrida.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultsQuestionsTest {

    @TempDir
    static Path dir;
    AppContext ctx;
    Analisis analisis;
    List<DecisionGuardada> r;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("q.db"));
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 24, LocalDate.of(2025, 6, 30));
        List<SheetData> hojas = ExcelReader.leer(file);
        for (ImportType tipo : ImportType.values()) {
            SheetData h = hojas.stream().filter(s -> ImportType.detectar(s.nombre()) == tipo).findFirst().orElseThrow();
            ctx.importacion().importar(ctx.importacion().previsualizar(h, tipo,
                    ctx.importacion().mapeoSugerido(tipo, h.encabezados())), "demo.xlsx", h.encabezados());
        }
        ctx.planificacion().generarYGuardar();
        analisis = ctx.resultados().ultimo().orElseThrow();
        r = ctx.resultados().resultados(analisis.id());
    }

    @Test
    void cadaPreguntaCumpleSuCriterio() {
        for (DecisionGuardada x : ResultsService.pregunta(r, Pregunta.COMPRAR)) {
            assertTrue(x.cantidadSugerida() > 0);
        }
        List<DecisionGuardada> top = ResultsService.pregunta(r, Pregunta.MAYOR_DEMANDA);
        assertFalse(top.isEmpty());
        assertTrue(top.size() <= ResultsService.TOP_DEMANDA);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).detalle().p30() >= top.get(i).detalle().p30());
        }
        for (DecisionGuardada x : ResultsService.pregunta(r, Pregunta.EXCESO)) {
            assertTrue(x.stock() > 0);
        }
        for (DecisionGuardada x : ResultsService.pregunta(r, Pregunta.PERDIENDO_DEMANDA)) {
            assertEquals("DECRECIENTE", x.detalle().tendencia());
        }
        for (DecisionGuardada x : ResultsService.pregunta(r, Pregunta.CRECIMIENTO)) {
            assertTrue("CRECIENTE".equals(x.detalle().tendencia()) || x.decision() == DecisionEstrategica.PUBLICITAR);
        }
        for (DecisionGuardada x : ResultsService.pregunta(r, Pregunta.NO_RECOMPRAR)) {
            assertTrue(x.decision() == DecisionEstrategica.LIQUIDAR || x.decision() == DecisionEstrategica.REDUCIR
                    || x.decision() == DecisionEstrategica.DESCONTINUAR);
        }
        for (Pregunta q : Pregunta.values()) {
            assertFalse(ResultsService.resumen(q, ResultsService.pregunta(r, q)).isBlank());
            assertFalse(ResultsService.resumen(q, List.of()).isBlank());
        }
    }

    @Test
    void explicacionSencillaDiceQueHacerYCuantoConfiar() {
        for (DecisionGuardada x : r) {
            String e = ResultsService.explicacionSencilla(x);
            assertFalse(e.isBlank());
            assertTrue(e.contains("Predicción") || e.contains("predicción") || e.contains("estimación"), e);
        }
        DecisionGuardada compra = ResultsService.pregunta(r, Pregunta.COMPRAR).get(0);
        assertTrue(ResultsService.explicacionSencilla(compra).startsWith("Compre "
                + com.stockwise.util.Formatters.integer(compra.cantidadSugerida()) + " unidades"));
    }

    @Test
    void fuentesDistinguenDemandaDeComprasEInventario() {
        List<FuenteDatos> f = ctx.resultados().fuentes(analisis, r);
        List<String> nombres = f.stream().map(FuenteDatos::nombre).toList();
        assertTrue(nombres.containsAll(List.of("Ventas", "Devoluciones", "Inventario", "Compras",
                "Faltantes de stock", "Historial por producto")), nombres.toString());
        FuenteDatos ventas = f.get(0);
        assertTrue(ventas.disponible());
        assertTrue(ventas.uso().contains("demanda"));
        FuenteDatos compras = f.stream().filter(x -> x.nombre().equals("Compras")).findFirst().orElseThrow();
        assertTrue(compras.uso().contains("Nunca"));
        // Sin base de datos solo se informa lo que consta en la corrida (no se inventa).
        ResultsService sinBase = new ResultsService(null, null, null, null);
        assertEquals(2, sinBase.fuentes(analisis, r).size());
    }

    @Test
    void rangosYPrecisionPorHorizonteSeGuardanConLaCorrida() {
        int conRango = 0;
        for (DecisionGuardada x : r) {
            DetalleAnalisis d = x.detalle();
            if (d.p30() == null) {
                continue;
            }
            List<HorizonForecast.Pronostico> h = ResultadosBuilder.pronosticosGuardados(d, x.fechaCorte());
            assertEquals(HorizonForecast.HORIZONTES.length, h.size());
            assertEquals(d.p30(), h.get(2).valor(), 1e-9);
            if (h.get(2).inferior() != null) {
                conRango++;
                for (HorizonForecast.Pronostico p : h) {
                    assertTrue(p.inferior() <= p.valor() + 1e-6 && p.valor() <= p.superior() + 1e-6, p.toString());
                }
            }
            if (!ResultadosBuilder.PROVISIONAL.equals(d.confianza())) {
                List<ResultadosBuilder.MetricaHorizonte> m = ResultadosBuilder.leerMetricasHorizonte(
                        d.metricasHorizonte());
                assertEquals(3, m.size());
                assertEquals(1, m.get(0).meses());
            }
        }
        assertTrue(conRango > 0);
    }

    /** Un producto con solo un mes de ventas recibe una estimación provisional, marcada como tal y con rango. */
    @Test
    void productoNuevoRecibeEstimacionProvisional() {
        LocalDate corte = ctx.ventaRepository().fechaMaxima().orElseThrow();
        long suc = ctx.database().queryLong("SELECT id FROM sucursales ORDER BY id LIMIT 1");
        long id = ctx.productos().guardar(new com.stockwise.model.Producto(0, "NUEVO-1", "Producto recién lanzado",
                null, null, null, null, 20, 10, 0, 0, 7, true), "General", null);
        for (int i = 0; i < 30; i++) {
            ctx.ventaRepository().insert(corte.minusDays(i), id, suc, 2, 20, "N" + i);
        }
        ctx.planificacion().generarYGuardar();
        long ultimo = ctx.resultados().ultimo().orElseThrow().id();
        DecisionGuardada x = ctx.resultados().resultados(ultimo).stream().filter(d -> d.productoId() == id)
                .findFirst().orElseThrow();
        DetalleAnalisis d = x.detalle();
        assertEquals(ResultadosBuilder.PROVISIONAL, d.confianza());
        assertTrue(d.perfil().startsWith("Producto nuevo"), d.perfil());
        assertEquals(60, d.p30(), 1e-6);
        assertEquals(14, d.p7(), 1e-6);
        List<HorizonForecast.Pronostico> h = ResultadosBuilder.pronosticosGuardados(d, x.fechaCorte());
        assertTrue(h.get(2).inferior() < 60 && h.get(2).superior() > 60);
        assertTrue(ResultsService.explicacionSencilla(x).contains("provisional"));
    }

    @Test
    void preguntaClaveAbreDesdeInicio() {
        assertNotNull(Pregunta.valueOf("MAYOR_DEMANDA").descripcion());
        assertEquals(8, Pregunta.values().length);
    }
}
