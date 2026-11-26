package com.stockwise.importer;

import com.stockwise.service.Progreso;
import com.stockwise.service.ValidationException;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lectura por flujo de XLSX y CSV sin cargar el libro completo en memoria. */
class LectorStreamingTest {

    @TempDir
    Path dir;

    private static void escribir(Workbook wb, Path f) throws Exception {
        try (OutputStream o = Files.newOutputStream(f)) {
            wb.write(o);
        }
    }

    @Test
    void xlsxConvierteLosValoresIgualQueElLectorAnterior() throws Exception {
        Path f = dir.resolve("valores.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Sheet oculta = wb.createSheet("Oculta");
            oculta.createRow(0).createCell(0).setCellValue("x");
            wb.setSheetHidden(0, true);
            Sheet sh = wb.createSheet("Ventas");
            sh.createRow(0).createCell(0).setCellValue("REPORTE");          // título (1 texto): no es encabezado
            Row h = sh.createRow(2);
            String[] enc = {"Fecha", "Código", "Cantidad", "Activo", "Nota"};
            for (int i = 0; i < enc.length; i++) {
                h.createCell(i).setCellValue(enc[i]);
            }
            Row r = sh.createRow(3);
            r.createCell(0).setCellValue(LocalDate.of(2025, 3, 7));
            r.getCell(0).setCellStyle(fecha);
            r.createCell(1).setCellValue("  A1  ");
            r.createCell(2).setCellValue(3.5);
            r.createCell(3).setCellValue(true);
            // fila 5 vacía (no aparece), fila 6 con celdas dispersas
            Row r2 = sh.createRow(5);
            r2.createCell(1).setCellValue("B2");
            r2.createCell(4).setCellValue("fin");
            escribir(wb, f);
        }
        List<LectorStreaming.Hoja> hojas = LectorStreaming.inspeccionar(f, 100, Progreso.NINGUNO);
        assertEquals(1, hojas.size(), "Las hojas ocultas se omiten");
        LectorStreaming.Hoja hoja = hojas.get(0);
        assertEquals(3, hoja.filaEncabezado());
        assertEquals(List.of("Fecha", "Código", "Cantidad", "Activo", "Nota"), hoja.encabezados());
        assertEquals(2, hoja.totalFilas());
        assertTrue(hoja.completa());
        List<Object> a = hoja.muestra().filas().get(0);
        assertEquals(LocalDate.of(2025, 3, 7), a.get(0));
        assertEquals("A1", a.get(1));
        assertEquals(3.5, a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertNull(a.get(4));
        List<Object> b = hoja.muestra().filas().get(1);
        assertEquals(6, hoja.muestra().numerosFila().get(1));
        assertNull(b.get(0));
        assertEquals("B2", b.get(1));
        assertEquals("fin", b.get(4));
        // Misma lectura que el lector completo.
        assertEquals(hoja.muestra().filas(), ExcelReader.leer(f).get(0).filas());
    }

    @Test
    void xlsxConTextosEnLineaYFechasDelSistema1904() throws Exception {
        Path f = dir.resolve("inline.xlsx");
        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 100, true, false)) {
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Sheet sh = wb.createSheet("Datos");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("Fecha");
            h.createCell(1).setCellValue("Producto");
            Row r = sh.createRow(1);
            r.createCell(0).setCellValue(LocalDate.of(2024, 12, 31));
            r.getCell(0).setCellStyle(fecha);
            r.createCell(1).setCellValue("Café ☕");
            escribir(wb, f);
            wb.dispose();
        }
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, 10, Progreso.NINGUNO).get(0);
        assertEquals(LocalDate.of(2024, 12, 31), h.muestra().filas().get(0).get(0));
        assertEquals("Café ☕", h.muestra().filas().get(0).get(1));

        Path g = dir.resolve("1904.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var pr = wb.getCTWorkbook().isSetWorkbookPr() ? wb.getCTWorkbook().getWorkbookPr()
                    : wb.getCTWorkbook().addNewWorkbookPr();
            pr.setDate1904(true);
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Sheet sh = wb.createSheet("D");
            Row hd = sh.createRow(0);
            hd.createCell(0).setCellValue("Fecha");
            hd.createCell(1).setCellValue("Dato");
            Row r = sh.createRow(1);
            // Número de serie en el sistema 1904 (independiente de cómo lo calcule POI al escribir).
            r.createCell(0).setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                    LocalDate.of(2025, 1, 15).atStartOfDay(), true));
            r.getCell(0).setCellStyle(fecha);
            r.createCell(1).setCellValue("x");
            escribir(wb, g);
        }
        assertEquals(LocalDate.of(2025, 1, 15),
                LectorStreaming.inspeccionar(g, 10, Progreso.NINGUNO).get(0).muestra().filas().get(0).get(0));
    }

    @Test
    void leeUnaHojaDeMasDe100MbSinCargarlaEnMemoria() throws Exception {
        // Caso exacto del error: una parte del ZIP (la hoja) supera los 100 MB descomprimida.
        Path f = dir.resolve("grande.xlsx");
        int filas = 330_000;
        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 200, true, false)) {
            Sheet sh = wb.createSheet("Ventas");
            Row h = sh.createRow(0);
            String[] enc = {"Fecha", "Código de producto", "Sucursal", "Cantidad", "Precio unitario", "Total",
                    "Referencia"};
            for (int i = 0; i < enc.length; i++) {
                h.createCell(i).setCellValue(enc[i]);
            }
            for (int r = 0; r < filas; r++) {
                Row row = sh.createRow(r + 1);
                row.createCell(0).setCellValue("2025-01-" + String.format("%02d", 1 + r % 28));
                row.createCell(1).setCellValue("P" + (r % 50));
                row.createCell(2).setCellValue("PRINCIPAL");
                row.createCell(3).setCellValue(1 + r % 9);
                row.createCell(4).setCellValue(10.5);
                row.createCell(5).setCellValue((1 + r % 9) * 10.5);
                row.createCell(6).setCellValue("DOCUMENTO-REFERENCIA-" + r);
            }
            escribir(wb, f);
            wb.dispose();
        }
        try (ZipFile z = new ZipFile(f.toFile())) {
            assertTrue(z.getEntry("xl/worksheets/sheet1.xml").getSize() > 100_000_000L, "La hoja supera 100 MB");
        }
        List<Double> avances = new ArrayList<>();
        Progreso p = new Progreso() {
            @Override
            public void avance(double porcentaje, String mensaje) {
                avances.add(porcentaje);
            }

            @Override
            public boolean cancelado() {
                return false;
            }
        };
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, 1000, p).get(0);
        assertEquals(filas, h.totalFilas());
        assertFalse(h.completa());
        assertEquals(1000, h.muestra().totalFilas(), "Solo se conserva la muestra");
        for (int i = 1; i < avances.size(); i++) {
            assertTrue(avances.get(i) >= avances.get(i - 1), "Avance real y monótono");
        }
        assertTrue(avances.size() > 20);
        long[] n = {0};
        double[] suma = {0};
        LectorStreaming.recorrer(f, h, (num, v) -> {
            n[0]++;
            suma[0] += (Double) v.get(3);
        }, Progreso.NINGUNO);
        double esperado = 0;
        for (int r = 0; r < filas; r++) {
            esperado += 1 + r % 9;
        }
        assertEquals(filas, n[0]);
        assertEquals(esperado, suma[0], 1e-6);
    }

    @Test
    void cancelarDetieneLaLectura() throws Exception {
        Path f = dir.resolve("c.csv");
        StringBuilder sb = new StringBuilder("a;b\n");
        for (int i = 0; i < 300_000; i++) {
            sb.append(i).append(";x\n");
        }
        Files.writeString(f, sb);
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, 10, Progreso.NINGUNO).get(0);
        int[] leidas = {0};
        Progreso cancelar = new Progreso() {
            @Override
            public void avance(double porcentaje, String mensaje) {
            }

            @Override
            public boolean cancelado() {
                return leidas[0] > 1000;
            }
        };
        assertThrows(CancellationException.class, () -> LectorStreaming.recorrer(f, h, (num, v) -> leidas[0]++,
                cancelar));
        assertTrue(leidas[0] < 300_000);
    }

    @Test
    void csvPorFlujoDetectaCodificacionAunqueElCaracterEsteAlFinal() throws Exception {
        Path f = dir.resolve("ventas.csv");
        StringBuilder sb = new StringBuilder("Fecha;Producto;Nota\n");
        for (int i = 0; i < 20_000; i++) {
            sb.append("2025-01-01;A").append(i).append(";\"texto; con separador\"\n");
        }
        sb.append("2025-01-02;Z;\"línea\nmúltiple\"\n");
        Files.write(f, sb.toString().getBytes(Charset.forName("windows-1252")));
        assertEquals(Charset.forName("windows-1252"), LectorStreaming.detectarCodificacion(f),
                "El byte no UTF-8 está después de los primeros 64 KB");
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, 50_000, Progreso.NINGUNO).get(0);
        assertEquals("ventas", h.nombre());
        assertEquals(List.of("Fecha", "Producto", "Nota"), h.encabezados());
        assertEquals(20_001, h.totalFilas());
        assertEquals("texto; con separador", h.muestra().filas().get(0).get(2));
        assertEquals("línea\nmúltiple", h.muestra().filas().get(20_000).get(2));
        Path u = dir.resolve("utf8.csv");
        Files.writeString(u, "﻿código,nombre\n1,ñandú\n", StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, LectorStreaming.detectarCodificacion(u));
        LectorStreaming.Hoja hu = LectorStreaming.inspeccionar(u, 10, Progreso.NINGUNO).get(0);
        assertEquals(List.of("código", "nombre"), hu.encabezados(), "El BOM UTF-8 no forma parte del encabezado");
        assertEquals("ñandú", hu.muestra().filas().get(0).get(1));
    }

    @Test
    void informaCuandoElArchivoSuperaLaMemoriaSegura() {
        LectorStreaming.verificarMemoriaXlsx(10L << 20, 512L << 20);
        ValidationException e = assertThrows(ValidationException.class,
                () -> LectorStreaming.verificarMemoriaXlsx(300L << 20, 512L << 20));
        assertTrue(e.getMessage().contains("CSV"), "Ofrece una alternativa compatible: " + e.getMessage());
    }
}
