package com.stockwise.service;

import com.stockwise.exporter.ReportTable;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.model.Producto;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.service.AccionesService.Accion;
import com.stockwise.service.AccionesService.Estado;
import com.stockwise.service.AccionesService.TipoAccion;
import com.stockwise.service.ResultsService.Ranking;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acciones recomendadas, rankings con cifras en unidades y reportes del análisis. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccionesYRankingsTest {

    @TempDir
    static Path dir;
    AppContext ctx;
    List<DecisionGuardada> r;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("acc.db"));
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 24, LocalDate.of(2025, 6, 30));
        for (SheetData h : ExcelReader.leer(file)) {
            ImportType t = ImportType.detectar(h.nombre());
            if (t != null) {
                ctx.importacion().importar(ctx.importacion().previsualizar(h, t,
                        ctx.importacion().mapeoSugerido(t, h.encabezados())), "demo.xlsx", h.encabezados());
            }
        }
        ctx.planificacion().generarYGuardar();
        r = ctx.resultados().resultados(ctx.resultados().ultimo().orElseThrow().id());
    }

    @Test
    void cadaAccionExplicaTodoLoPedido() {
        List<Accion> l = ctx.acciones().acciones();
        assertFalse(l.isEmpty());
        for (Accion a : l) {
            assertFalse(a.producto().isBlank());
            for (String s : List.of(a.situacion(), a.recomendacion(), a.motivo(), a.errorPrediccion(), a.riesgo(),
                    a.resultadoEsperado())) {
                assertFalse(s == null || s.isBlank(), a.tipo() + " con campo vacío");
            }
            assertEquals(Estado.PENDIENTE, a.estado());
        }
        for (int i = 1; i < l.size(); i++) {
            assertTrue(l.get(i - 1).prioridad() <= l.get(i).prioridad(), "ordenadas por urgencia");
        }
        Map<TipoAccion, Long> tipos = l.stream().collect(Collectors.groupingBy(Accion::tipo, Collectors.counting()));
        assertTrue(tipos.containsKey(TipoAccion.COMPRAR_MAS), tipos.toString());
        Accion compra = l.stream().filter(a -> a.tipo() == TipoAccion.COMPRAR_MAS && a.cantidad() > 0).findFirst()
                .orElseThrow();
        assertTrue(compra.recomendacion().contains(compra.cantidad() + " u"), compra.recomendacion());
    }

    @Test
    void prediccionPocoConfiableSeAdvierte() {
        for (Accion a : ctx.acciones().acciones()) {
            if (a.origen() != AccionesService.Origen.DECISION) {
                continue;
            }
            DecisionGuardada d = r.stream().filter(x -> x.id() == a.id()).findFirst().orElseThrow();
            if (d.detalle().wape() != null && d.detalle().wape() > AccionesService.ERROR_ALTO) {
                assertTrue(a.errorAlto());
            }
            if (d.detalle().wape() != null) {
                assertTrue(a.errorPrediccion().contains(Math.round(d.detalle().wape() * 100) + " %"),
                        a.errorPrediccion());
                assertTrue(a.errorPrediccion().contains("mes"), "debe indicar el periodo de validación");
            }
        }
    }

    @Test
    void marcarSoloRegistraLaDecisionSinTocarInventarioNiPrecios() {
        Accion a = ctx.acciones().acciones().stream().filter(x -> x.origen() == AccionesService.Origen.DECISION)
                .findFirst().orElseThrow();
        Map<Long, Integer> stockAntes = ctx.inventario().listar(null).stream()
                .collect(Collectors.groupingBy(i -> i.productoId(), Collectors.summingInt(i -> i.stock())));
        Producto pAntes = ctx.productos().buscar(a.productoId()).orElseThrow();
        long ventasAntes = ctx.ventaRepository().count();
        for (Estado e : List.of(Estado.POSPUESTA, Estado.RECHAZADA, Estado.ACEPTADA)) {
            ctx.acciones().marcar(a, e);
            Accion despues = ctx.acciones().acciones().stream().filter(x -> x.origen() == a.origen()
                    && x.id() == a.id()).findFirst().orElseThrow();
            assertEquals(e, despues.estado());
        }
        Map<Long, Integer> stockDespues = ctx.inventario().listar(null).stream()
                .collect(Collectors.groupingBy(i -> i.productoId(), Collectors.summingInt(i -> i.stock())));
        assertEquals(stockAntes, stockDespues);
        assertEquals(pAntes, ctx.productos().buscar(a.productoId()).orElseThrow());
        assertEquals(ventasAntes, ctx.ventaRepository().count());
        ctx.acciones().marcar(a, Estado.PENDIENTE);
    }

    @Test
    void rankingsMuestranUnidadesYOrdenCorrecto() {
        assertEquals(7, ResultsService.VISIBLES.size());
        for (Ranking k : ResultsService.VISIBLES) {
            List<DecisionGuardada> l = ResultsService.ranking(r, k, 90, 0);
            assertFalse(ResultsService.unidadRanking(k).contains("%"));
            if (k == Ranking.MAS_VENDIDOS || k == Ranking.MAYOR_DEMANDA || k == Ranking.EXCESO
                    || k == Ranking.MAYOR_CRECIMIENTO) {
                for (int i = 1; i < l.size(); i++) {
                    assertTrue(ResultsService.valorRanking(l.get(i - 1), k) >= ResultsService.valorRanking(l.get(i), k),
                            k + " desordenado");
                }
            }
        }
        assertFalse(ResultsService.ranking(r, Ranking.MAYOR_DEMANDA, 90, 0).isEmpty());
        for (DecisionGuardada x : ResultsService.ranking(r, Ranking.EXCESO, 90, 0)) {
            assertTrue(ResultsService.unidadesDeMas(x) >= 1);
        }
        for (DecisionGuardada x : ResultsService.ranking(r, Ranking.PERDIENDO_VENTAS, 90, 0)) {
            assertEquals("DECRECIENTE", x.detalle().tendencia());
        }
    }

    @Test
    void reportesDelAnalisisConCifrasOriginales() {
        for (ReportesAnalisisService.Tipo t : ReportesAnalisisService.Tipo.values()) {
            List<ReportTable> l = ctx.reportesAnalisis().generar(t);
            assertFalse(l.isEmpty());
            assertEquals(t == ReportesAnalisisService.Tipo.INFORME ? 5 : 1, l.size());
        }
        ReportTable compras = ctx.reportesAnalisis().generar(ReportesAnalisisService.Tipo.COMPRAS).get(0);
        List<DecisionGuardada> comprar = ResultsService.pregunta(r, ResultsService.Pregunta.COMPRAR);
        assertEquals(comprar.size(), compras.filas().size());
        assertEquals(comprar.get(0).cantidadSugerida(), compras.filas().get(0).get(2));
        ReportTable mas = ctx.reportesAnalisis().generar(ReportesAnalisisService.Tipo.MAS_VENDIDOS).get(0);
        assertEquals(ResultsService.ranking(r, Ranking.MAS_VENDIDOS, 0, 0).get(0).producto(), mas.filas().get(0).get(2));
    }

    @Test
    void elAnalisisGeneraTambienLasSugerenciasDelAsesor() {
        assertFalse(ctx.recomendaciones().recomendaciones().isEmpty());
    }
}
