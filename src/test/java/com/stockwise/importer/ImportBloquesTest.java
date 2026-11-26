package com.stockwise.importer;

import com.stockwise.model.Producto;
import com.stockwise.repository.ImportacionRepository;
import com.stockwise.service.AppContext;
import com.stockwise.service.Progreso;
import com.stockwise.service.ValidationException;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importación por bloques: exactitud, repetidos, errores, cancelación, continuación y equivalencia. */
class ImportBloquesTest {

    @TempDir
    Path dir;
    AppContext ctx;

    static final int FILAS = 25_000;
    /** Fila con devolución (cantidad negativa). */
    static final int DEV = FILAS - 2;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("b.db"));
        for (int i = 0; i < 20; i++) {
            ctx.productos().guardar(new Producto(0, "P" + i, "Prod " + i, null, null, null, null, 10, 5, 0, 0, null,
                    true), "Cat", "Prov");
        }
    }

    /**
     * Ventas: la fila 7 se repite en la 20.007 (otro bloque), la 100 se repite en la 101 (mismo bloque), cada 1.000
     * filas hay un producto inexistente y la penúltima fila es una devolución.
     */
    private Path archivo(String nombre) throws Exception {
        Path f = dir.resolve(nombre);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 200, true, true)) {
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Sheet sh = wb.createSheet("Ventas");
            Row h = sh.createRow(0);
            String[] enc = {"Fecha", "Código de producto", "Cantidad", "Precio unitario", "Referencia"};
            for (int i = 0; i < enc.length; i++) {
                h.createCell(i).setCellValue(enc[i]);
            }
            for (int r = 0; r < FILAS; r++) {
                int base = r == 20_007 ? 7 : r == 101 ? 100 : r;
                Row row = sh.createRow(r + 1);
                row.createCell(0).setCellValue(LocalDate.of(2024, 1, 1).plusDays(base % 500));
                row.getCell(0).setCellStyle(fecha);
                row.createCell(1).setCellValue(r % 1000 == 999 ? "NO-EXISTE" : "P" + (base % 20));
                row.createCell(2).setCellValue(r == DEV ? -2 : 1 + base % 5);
                row.createCell(3).setCellValue(10);
                row.createCell(4).setCellValue("F" + base);
            }
            try (OutputStream o = Files.newOutputStream(f)) {
                wb.write(o);
            }
            wb.dispose();
        }
        return f;
    }

    private LectorStreaming.Hoja hoja(Path f) {
        return LectorStreaming.inspeccionar(f, 1000, Progreso.NINGUNO).get(0);
    }

    private Map<String, Integer> mapeo(LectorStreaming.Hoja h) {
        return ctx.importacion().mapeoSugerido(ImportType.VENTAS, h.encabezados());
    }

    @Test
    void importaTodasLasFilasCorrectamenteYDetectaRepetidosEntreBloques() throws Exception {
        Path f = archivo("ventas.xlsx");
        LectorStreaming.Hoja h = hoja(f);
        assertFalse(h.completa());
        var r = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), Progreso.NINGUNO);
        assertTrue(r.completa());
        assertEquals(FILAS, r.filas());
        assertEquals(25, r.errores(), "Una fila con producto inexistente cada 1.000");
        assertEquals(2, r.duplicados(), "Repetida en el mismo bloque y en otro bloque");
        assertEquals(FILAS - 25 - 2, r.insertados(), "Incluye la devolución");
        assertEquals(FILAS - 25 - 2 - 1, ctx.ventaRepository().count());
        assertEquals(1, ctx.database().queryLong("SELECT COUNT(*) FROM devoluciones"));
        // Suma exacta de unidades vendidas.
        double esperado = 0;
        for (int i = 0; i < FILAS; i++) {
            if (i % 1000 != 999 && i != 20_007 && i != 101 && i != DEV) {
                esperado += 1 + i % 5;
            }
        }
        assertEquals(esperado, ctx.database().queryDouble("SELECT SUM(cantidad) FROM ventas"), 1e-9);
        assertTrue(r.observaciones().stream().anyMatch(o -> o.estado() == ImportRow.Estado.ERROR));
        var imp = ctx.historialImportaciones().listar().get(0);
        assertEquals(ImportacionRepository.COMPLETADA, imp.estado());
        assertEquals(r.insertados(), imp.insertados());
        assertEquals(LocalDate.of(2024, 1, 1), imp.periodoDesde());
        // Importación acumulativa: el mismo archivo otra vez no duplica nada.
        var otra = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), Progreso.NINGUNO);
        assertEquals(0, otra.insertados());
        assertEquals(FILAS - 25, otra.duplicados());
        assertEquals(FILAS - 25 - 3, ctx.ventaRepository().count());
    }

    @Test
    void cancelarConservaBloquesCompletosYSePuedeContinuar() throws Exception {
        Path f = archivo("cancelar.xlsx");
        LectorStreaming.Hoja h = hoja(f);
        Progreso cancelarTrasPrimerBloque = new Progreso() {
            @Override
            public void avance(double porcentaje, String mensaje) {
            }

            @Override
            public boolean cancelado() {
                return ctx.ventaRepository().count() > 0;
            }
        };
        var r = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), cancelarTrasPrimerBloque);
        assertFalse(r.completa());
        assertTrue(r.mensaje().contains("PARCIAL"));
        assertEquals(ImportService.TAMANO_BLOQUE, r.filas(), "Nunca queda un bloque a medias");
        assertEquals(ImportacionRepository.PARCIAL, ctx.historialImportaciones().listar().get(0).estado());
        long guardadas = ctx.ventaRepository().count();
        // Continuar: reimportar el archivo completa lo que faltaba sin duplicar.
        var resto = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), Progreso.NINGUNO);
        assertTrue(resto.completa());
        assertEquals(FILAS - 25 - 3, ctx.ventaRepository().count());
        assertTrue(resto.insertados() > 0 && resto.insertados() < FILAS - guardadas);
        // La importación parcial puede eliminarse desde el historial.
        long parcial = ctx.historialImportaciones().listar().get(1).id();
        var imp = ctx.historialImportaciones().impacto(parcial);
        ctx.historialImportaciones().eliminar(parcial);
        assertEquals(FILAS - 25 - 3 - imp.ventasEliminar(), ctx.ventaRepository().count());
    }

    @Test
    void produceElMismoResultadoQueLaImportacionNormal() throws Exception {
        Path f = dir.resolve("chico.csv");
        StringBuilder sb = new StringBuilder("Fecha;Código de producto;Cantidad;Precio unitario;Referencia\n");
        for (int i = 0; i < 3000; i++) {
            sb.append("2025-0").append(1 + i % 9).append("-15;P").append(i % 20).append(';').append(1 + i % 4)
                    .append(";10;R").append(i % 2500).append('\n');
        }
        Files.writeString(f, sb);
        AppContext otro = new AppContext(dir.resolve("normal.db"));
        for (int i = 0; i < 20; i++) {
            otro.productos().guardar(new Producto(0, "P" + i, "Prod " + i, null, null, null, null, 10, 5, 0, 0, null,
                    true), "Cat", "Prov");
        }
        SheetData sd = ExcelReader.leer(f).get(0);
        ImportPreview p = otro.importacion().previsualizar(sd, ImportType.VENTAS,
                otro.importacion().mapeoSugerido(ImportType.VENTAS, sd.encabezados()));
        otro.importacion().importar(p, "chico.csv", sd.encabezados());

        LectorStreaming.Hoja h = hoja(f);
        var r = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), Progreso.NINGUNO);
        assertEquals(p.count(ImportRow.Estado.NUEVO), r.insertados());
        assertEquals(p.count(ImportRow.Estado.DUPLICADO), r.duplicados());
        assertEquals(otro.ventaRepository().count(), ctx.ventaRepository().count());
        assertEquals(otro.database().queryDouble("SELECT SUM(total) FROM ventas"),
                ctx.database().queryDouble("SELECT SUM(total) FROM ventas"), 1e-9);
    }

    @Test
    void siFallaAntesDeGuardarNoQuedaRastroYSeInformaElMotivo() throws Exception {
        Path f = archivo("falla.xlsx");
        LectorStreaming.Hoja h = hoja(f);
        Files.delete(f); // el archivo deja de estar disponible entre la inspección y la importación
        ValidationException e = assertThrows(ValidationException.class, () -> ctx.importacion()
                .importarPorBloques(f, h, ImportType.VENTAS, mapeo(h), Progreso.NINGUNO));
        assertTrue(e.getMessage().contains("no existe"), e.getMessage());
        assertTrue(ctx.historialImportaciones().listar().isEmpty());
        assertEquals(0, ctx.ventaRepository().count());
    }

    @Test
    void validaLasColumnasObligatoriasAntesDeEmpezar() throws Exception {
        Path f = archivo("v.xlsx");
        LectorStreaming.Hoja h = hoja(f);
        assertThrows(ValidationException.class, () -> ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS,
                Map.of("fecha", 0), Progreso.NINGUNO));
        assertTrue(ctx.historialImportaciones().listar().isEmpty(), "No se registra nada si la relación es inválida");
    }

    @Test
    void velocidadYTiempoRestanteSoloConDatosSuficientes() {
        assertEquals("", ImportService.velocidad(0, 5_000_000_000L, 50));
        assertEquals("", ImportService.velocidad(1000, 500_000_000L, 50));
        assertEquals("", ImportService.velocidad(1000, 5_000_000_000L, 0.5));
        String v = ImportService.velocidad(50_000, 10_000_000_000L, 25);
        assertTrue(v.contains("filas/s"), v);
        assertTrue(v.endsWith("quedan ≈ 30 s"), v);
        assertTrue(ImportService.velocidad(10_000, 60_000_000_000L, 20).endsWith("quedan ≈ 4 min 0 s"));
    }
}
