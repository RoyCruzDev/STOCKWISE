package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.exporter.ReportTable;
import com.stockwise.importer.ColumnMapper;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.recommendation.PurchasePlan;
import com.stockwise.repository.RecomendacionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flujo completo: importación del archivo de ejemplo → análisis → compras → asesor → reportes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndAnalysisTest {

    @TempDir
    static Path dir;
    AppContext ctx;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("e2e.db"));
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 24, LocalDate.now().withDayOfMonth(1).minusDays(1));
        List<SheetData> hojas = ExcelReader.leer(file);
        for (ImportType tipo : ImportType.values()) {
            SheetData h = hojas.stream().filter(s -> ImportType.detectar(s.nombre()) == tipo).findFirst().orElseThrow();
            ctx.importacion().importar(ctx.importacion().previsualizar(h, tipo,
                    ColumnMapper.sugerir(tipo, h.encabezados())));
        }
    }

    @Test
    void snapshotCalculaMetricasYPredicciones() {
        AnalysisSnapshot s = ctx.analysis().snapshot();
        assertEquals(16, s.productos().size());
        assertTrue(s.productos().stream().filter(m -> m.producto().codigo().equals("LAP-001"))
                .allMatch(m -> m.forecast().suficiente() && m.forecast().historia().length >= 23));
        // SIL-001 nunca se vendió -> sin pronóstico y sin movimiento
        assertTrue(s.productos().stream().filter(m -> m.producto().codigo().equals("SIL-001"))
                .allMatch(m -> !m.forecast().suficiente() && m.estado() == EstadoInventario.SIN_MOVIMIENTO));
        assertFalse(s.conEstado(EstadoInventario.SOBREINVENTARIO).isEmpty());
        assertFalse(s.conEstado(EstadoInventario.RIESGO_AGOTAMIENTO).isEmpty()
                && s.conEstado(EstadoInventario.AGOTADO).isEmpty());
    }

    @Test
    void planDeComprasNoSuperaPresupuesto() {
        PresupuestoService.EstadoPresupuesto ep = ctx.presupuestos().estadoActual();
        assertEquals(25000, ep.asignado(), 1e-6);
        PurchasePlan plan = ctx.recomendaciones().planDeCompras();
        assertFalse(plan.aComprar().isEmpty());
        assertTrue(plan.totalAprobado() <= ep.disponible() + 1e-6);
    }

    @Test
    void asesorGeneraRecomendacionesYAplicaTraslados() {
        List<Recomendacion> recs = ctx.recomendaciones().generarRecomendaciones();
        assertFalse(recs.isEmpty());
        Recomendacion traslado = recs.stream().filter(r -> r.tipo() == TipoRecomendacion.TRASLADO
                && r.producto().startsWith("AUD-002")).findFirst().orElseThrow();
        long origen = traslado.sucursalId();
        long destino = traslado.sucursalDestinoId();
        int antesOrigen = ctx.inventario().stock(traslado.productoId(), origen);
        int antesDestino = ctx.inventario().stock(traslado.productoId(), destino);
        ctx.recomendaciones().aplicar(traslado, 5);
        assertEquals(antesOrigen - 5, ctx.inventario().stock(traslado.productoId(), origen));
        assertEquals(antesDestino + 5, ctx.inventario().stock(traslado.productoId(), destino));
        assertTrue(ctx.recomendaciones().recomendaciones().stream()
                .anyMatch(r -> r.id() == traslado.id() && RecomendacionRepository.APLICADA.equals(r.estado())));
    }

    @Test
    void generaYExportaTodosLosReportes() throws Exception {
        LocalDate hasta = ctx.analysis().fechaCorte();
        LocalDate desde = hasta.minusMonths(12);
        List<ReportTable> tablas = new java.util.ArrayList<>();
        for (ReportService.ReportType t : ReportService.ReportType.values()) {
            ReportTable tabla = ctx.reportes().generar(t, desde, hasta);
            assertNotNull(tabla);
            tablas.add(tabla);
        }
        assertFalse(tablas.get(0).filas().isEmpty());

        Path xlsx = dir.resolve("reportes.xlsx");
        Path pdf = dir.resolve("reportes.pdf");
        ctx.reportes().exportarExcel(tablas, xlsx);
        ctx.reportes().exportarPdf(tablas, pdf);
        try (InputStream in = Files.newInputStream(xlsx); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertEquals(tablas.size(), wb.getNumberOfSheets());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertTrue(doc.getNumberOfPages() >= tablas.size());
        }
    }

    @Test
    void pronosticoGlobalDeVentas() {
        assertTrue(ctx.analysis().pronosticoGlobal(null, true).suficiente());
    }
}
