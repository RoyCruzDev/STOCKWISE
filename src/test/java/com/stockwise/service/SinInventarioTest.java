package com.stockwise.service;

import com.stockwise.importer.ColumnMapper;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SheetData;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.repository.DecisionRepository.DecisionGuardada;
import com.stockwise.service.ResultsService.Pregunta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proyecto con solo un reporte de ventas (sin inventario ni compras): la predicción se calcula con las ventas, pero
 * nada que dependa del stock se inventa suponiendo stock cero.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SinInventarioTest {

    @TempDir
    static Path dir;
    AppContext ctx;
    List<DecisionGuardada> r;
    LocalDate ultimaVenta;

    @BeforeAll
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("solo.db"));
        StringBuilder cat = new StringBuilder("Código;Nombre;Precio venta;Costo\n");
        for (int p = 0; p < 8; p++) {
            cat.append("P").append(p).append(";PRODUCTO ").append(p).append(";10;").append(p == 7 ? "9.5" : "6").append('\n');
        }
        Path fc = dir.resolve("cat.csv");
        Files.writeString(fc, cat.toString());
        SheetData hc = ExcelReader.leer(fc).get(0);
        ctx.importacion().importar(ctx.importacion().previsualizar(hc, ImportType.PRODUCTOS,
                ColumnMapper.sugerir(ImportType.PRODUCTOS, hc.encabezados())));
        StringBuilder v = new StringBuilder("Fecha;Código;Cantidad;Precio;Boleta\n");
        int n = 0;
        ultimaVenta = LocalDate.of(2026, 3, 2);
        for (LocalDate d = LocalDate.of(2024, 1, 1); !d.isAfter(ultimaVenta); d = d.plusDays(2)) {
            for (int p = 0; p < 7; p++) {
                // P6 deja de venderse a mitad del historial: candidato a "no comprar por ahora".
                if (p == 6 && d.isAfter(LocalDate.of(2025, 3, 1))) {
                    continue;
                }
                v.append(d).append(";P").append(p).append(';').append(1 + (n + p) % 3).append(";10;B-").append(n++)
                        .append('\n');
            }
        }
        v.append(ultimaVenta).append(";P7;1;10;B-X\n");
        Path fv = dir.resolve("ventas.csv");
        Files.writeString(fv, v.toString());
        SheetData hv = ExcelReader.leer(fv).get(0);
        ctx.importacion().importar(ctx.importacion().previsualizar(hv, ImportType.VENTAS,
                ColumnMapper.sugerir(ImportType.VENTAS, hv.encabezados())));
        ctx.planificacion().generarYGuardar();
        r = ctx.resultados().resultados(ctx.resultados().ultimo().orElseThrow().id());
    }

    @Test
    void lasPrediccionesUsanLasVentas() {
        assertFalse(ctx.hayInventario());
        assertTrue(r.stream().filter(x -> !x.codigo().equals("P6") && !x.codigo().equals("P7"))
                .allMatch(x -> x.detalle().p30() != null && x.detalle().p30() > 0));
        assertFalse(ResultsService.pregunta(r, Pregunta.MAYOR_DEMANDA, false).isEmpty());
    }

    @Test
    void loQueDependeDelStockQuedaPendiente() {
        for (Pregunta q : Pregunta.values()) {
            if (q.dependeDelStock()) {
                assertTrue(ResultsService.pregunta(r, q, false).isEmpty(), q.name());
                assertEquals(ResultsService.SIN_INVENTARIO, ResultsService.resumen(q, List.of(), false));
            }
        }
        assertTrue(ResultsService.pregunta(r, Pregunta.NO_RECOMPRAR, false).stream()
                .allMatch(x -> x.decision() == DecisionEstrategica.DESCONTINUAR));
        assertFalse(ResultsService.visibles(false).contains(ResultsService.Ranking.EXCESO));
        assertFalse(ResultsService.visibles(false).contains(ResultsService.Ranking.PARA_RECOMPRAR));
        for (DecisionGuardada x : r) {
            String e = ResultsService.explicacionSinInventario(x);
            assertFalse(e.contains("Compre "), e);
            assertTrue(e.contains("importe su inventario"), e);
        }
    }

    @Test
    void accionesSinInventarioNoProponenCompras() {
        assertFalse(ctx.acciones().inventarioRegistrado());
        for (AccionesService.Accion a : ctx.acciones().acciones()) {
            assertTrue(a.tipo() == AccionesService.TipoAccion.NO_COMPRAR || a.tipo() == AccionesService.TipoAccion.NEGOCIAR,
                    a.tipo().name());
            assertFalse(a.recomendacion().contains("próximo pedido sería de"), a.recomendacion());
        }
    }

    @Test
    void elGraficoMensualNoIncluyeElMesIncompleto() {
        var d = ctx.dashboard().calcular();
        String ultimo = d.mensual().get(d.mensual().size() - 1).periodo();
        assertTrue(ultimo.compareTo(YearMonth.from(ultimaVenta).toString()) < 0,
                "el mes " + YearMonth.from(ultimaVenta) + " está incompleto y no debe graficarse: " + ultimo);
    }
}
