package com.stockwise.importer;

import com.stockwise.service.AppContext;
import com.stockwise.service.Progreso;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importación universal: reportes de distintos sistemas (encabezados en español o inglés, columnas en otro orden,
 * filas de título, fechas y números en varios formatos, códigos de barras, hojas sin nombre reconocible) y
 * exactitud de lo guardado frente al original.
 */
class ImportUniversalTest {

    @TempDir
    Path dir;
    AppContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("u.db"));
        StringBuilder sb = new StringBuilder("Código;Nombre;Categoría;Precio venta;Costo\n");
        for (int p = 0; p < 10; p++) {
            sb.append(barras(p)).append(';').append("PRODUCTO ").append(p).append(";CAT;10;6\n");
        }
        sb.append("000123;PRODUCTO CON CEROS;CAT;10;6\n");
        Path cat = dir.resolve("catalogo.csv");
        Files.writeString(cat, sb.toString());
        SheetData h = ExcelReader.leer(cat).get(0);
        ctx.importacion().importar(ctx.importacion().previsualizar(h, ImportType.PRODUCTOS,
                ColumnMapper.sugerir(ImportType.PRODUCTOS, h.encabezados())));
    }

    static String barras(int p) {
        return String.valueOf(7751731000000L + p);
    }

    /** Importa la primera hoja con la detección automática de tipo y columnas. */
    private ImportService.ResultadoBloques importar(Path f, ImportService.Opciones op) {
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, LectorStreaming.MUESTRA, Progreso.NINGUNO).get(0);
        ImportType tipo = ImportType.detectar(h.nombre(), h.encabezados());
        assertNotNull(tipo, "tipo no reconocido: " + h.encabezados());
        return ctx.importacion().importarPorBloques(f, h, tipo, ctx.importacion().mapeoSugerido(tipo, h.encabezados()),
                op, Progreso.NINGUNO);
    }

    private double sumaVentas(String columna) {
        return ctx.database().queryDouble("SELECT COALESCE(SUM(" + columna + "),0) FROM ventas");
    }

    // ------------------------------------------------------------------ valores

    @Test
    void fechasEnFormatosHabituales() {
        LocalDate d = LocalDate.of(2026, 11, 6);
        for (String s : List.of("2026-11-06", "06/11/2026", "6/11/2026 15:19:20", "06-11-26", "06.11.2026",
                "20261106", "6-nov-2026", "6 de noviembre de 2026", "November 6, 2026", "2026-11-06T10:15:00",
                "6/11/2026 3:19 p. m.")) {
            assertEquals(d, ValueParser.fecha(s), s);
        }
        assertEquals(LocalDate.of(2026, 1, 13), ValueParser.fecha("1/13/2026"), "el 13 solo puede ser día");
        assertEquals(LocalDate.of(2026, 3, 4), ValueParser.fecha("03/04/2026", true), "formato EE. UU.");
        assertEquals(LocalDate.of(2026, 4, 3), ValueParser.fecha("03/04/2026", false));
        assertEquals(LocalDate.of(2025, 1, 2), ValueParser.fecha(45659.0));
    }

    @Test
    void numerosConComaDecimalYMiles() {
        assertEquals(1500, ValueParser.numero("1.500", true), 1e-9);
        assertEquals(1.5, ValueParser.numero("1.500", false), 1e-9);
        assertEquals(1234567, ValueParser.numero("1.234.567", false), 1e-9);
        assertEquals(1234.5, ValueParser.numero("1.234,50", false), 1e-9);
        assertEquals(12.5, ValueParser.numero("S/ 12,50", true), 1e-9);
        assertEquals(1234.5, ValueParser.numero("$1,234.50", false), 1e-9);
        FormatoArchivo f = FormatoArchivo.deducir(List.of(List.of("1.500", "12,50"), List.of("2", "3,10")),
                ImportType.VENTAS, Map.of("cantidad", 0, "precio", 1));
        assertTrue(f.comaDecimal());
        FormatoArchivo us = FormatoArchivo.deducir(List.of(List.of("4/13/2026"), List.of("4/1/2026")),
                ImportType.VENTAS, Map.of("fecha", 0));
        assertTrue(us.mesPrimero());
    }

    @Test
    void descuentoComoImporteOPorcentaje() {
        assertEquals(9.0, ImportService.precioConDescuento(10, 2, "2"), 1e-9);
        assertEquals(9.0, ImportService.precioConDescuento(10, 1, "10%"), 1e-9);
        assertNull(ImportService.precioConDescuento(10, 1, "mucho"));
    }

    // ------------------------------------------------------------------ archivos completos

    /** Reporte con filas de título ("Empresa: ...", "RUC: ...") antes de los encabezados y fila de totales al final. */
    @Test
    void saltaFilasDeTituloYReconoceElEncabezado() throws Exception {
        Path f = dir.resolve("erp.xlsx");
        double unidades = 0;
        double importe = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("reporte");
            s.createRow(0).createCell(0).setCellValue("REPORTE DE VENTAS POR PRODUCTO");
            Row e = s.createRow(1);
            e.createCell(0).setCellValue("Empresa: TIENDA SAN JOSE");
            e.createCell(2).setCellValue("RUC: 20603376162");
            String[] h = {"N", "Fecha Hora", "Comprobante", "Código de barra", "Producto", "SKU", "Cantidad",
                    "Precio x Unidad", "Descuento", "Total"};
            Row hr = s.createRow(3);
            for (int i = 0; i < h.length; i++) {
                hr.createCell(i).setCellValue(h[i]);
            }
            for (int i = 0; i < 60; i++) {
                Row r = s.createRow(4 + i);
                int p = i % 10;
                int cant = 1 + i % 3;
                double total = cant * 10 - (i % 5 == 0 ? 2 : 0);
                unidades += cant;
                importe += total;
                r.createCell(0).setCellValue(i + 1);
                r.createCell(1).setCellValue((1 + i % 28) + "/3/2026 " + (8 + i % 10) + ":05:00");
                r.createCell(2).setCellValue("B001-" + i);
                r.createCell(3).setCellValue(Double.parseDouble(barras(p)));
                r.createCell(4).setCellValue("PRODUCTO " + p);
                r.createCell(5).setCellValue("SKU-" + p);
                r.createCell(6).setCellValue(cant);
                r.createCell(7).setCellValue(10);
                r.createCell(8).setCellValue(i % 5 == 0 ? 2 : 0);
                r.createCell(9).setCellValue(total);
            }
            Row t = s.createRow(66);
            t.createCell(0).setCellValue("TOTAL GENERAL");
            t.createCell(6).setCellValue(unidades);
            try (OutputStream o = Files.newOutputStream(f)) {
                wb.write(o);
            }
        }
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, LectorStreaming.MUESTRA, Progreso.NINGUNO).get(0);
        assertEquals(4, h.filaEncabezado());
        Map<String, Integer> m = ColumnMapper.sugerir(ImportType.VENTAS, h.encabezados());
        assertEquals("SKU", h.encabezados().get(m.get("producto")));
        assertEquals("Código de barra", h.encabezados().get(m.get("codigo_barras")));
        assertEquals("Descuento", h.encabezados().get(m.get("descuento")));
        var r = importar(f, ImportService.Opciones.NINGUNA);
        // El SKU no está en el catálogo, pero el código de barras sí: todas se reconocen.
        assertEquals(0, r.errores(), r.explicacion());
        assertEquals(60, r.insertados());
        assertEquals(unidades, sumaVentas("cantidad"), 1e-6);
        assertEquals(importe, sumaVentas("total"), 0.01, "el total respeta los descuentos");
    }

    /** CSV en inglés con comas, fecha ISO con hora y nombres entre comillas que contienen comas. */
    @Test
    void csvEnInglesConComillas() throws Exception {
        StringBuilder sb = new StringBuilder("Order Date,Item Code,Product Name,Qty,Unit Price,Amount,Invoice\n");
        for (int i = 0; i < 40; i++) {
            sb.append("2026-02-").append(String.format("%02d", 1 + i % 28)).append("T10:00:00,").append(barras(i % 10))
                    .append(",\"PRODUCTO ").append(i % 10).append(", unidad\",2,4.25,8.50,INV-").append(i).append('\n');
        }
        Path f = dir.resolve("sales.csv");
        Files.writeString(f, sb.toString());
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(40, r.insertados(), r.explicacion());
        assertEquals(80, sumaVentas("cantidad"), 1e-9);
        assertEquals(340, sumaVentas("total"), 1e-6);
    }

    /** CSV latino: punto y coma, Windows-1252, fecha dd-mm-aa, "1.500" de miles y "S/ 12,50". */
    @Test
    void csvLatinoConMilesYComaDecimal() throws Exception {
        StringBuilder sb = new StringBuilder("Fecha;Cód. Artículo;Descripción;Cant.;P. Unit.;Importe;N° Doc\n");
        sb.append("05-01-26;").append(barras(1)).append(";PRODUCTO 1;1.500;S/ 1,50;2.250,00;F-1\n");
        sb.append("06-01-26;").append(barras(2)).append(";PRODUCTO 2;3;S/ 12,50;37,50;F-2\n");
        Path f = dir.resolve("ventas.csv");
        Files.writeString(f, sb.toString(), Charset.forName("windows-1252"));
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(2, r.insertados(), r.explicacion());
        assertEquals(1503, sumaVentas("cantidad"), 1e-9);
        assertEquals(2287.5, sumaVentas("total"), 1e-6);
        assertEquals("2026-01-05", ctx.database().queryOne("SELECT MIN(fecha) FROM ventas", rs -> rs.getString(1))
                .orElseThrow());
    }

    /** XLS con hojas "Hoja1" (ventas, meses en texto, sin documento) y "Hoja2" (inventario con decimales). */
    @Test
    void xlsConHojasGenericasYLineasSinDocumento() throws Exception {
        Path f = dir.resolve("libro.xls");
        try (Workbook wb = new HSSFWorkbook()) {
            Sheet v = wb.createSheet("Hoja1");
            String[] h = {"Fecha de emisión", "Código", "Artículo", "Unidades", "Precio", "Total"};
            Row hr = v.createRow(0);
            for (int i = 0; i < h.length; i++) {
                hr.createCell(i).setCellValue(h[i]);
            }
            for (int i = 0; i < 20; i++) {
                // Dos ventas iguales por día (mismo producto, cantidad y precio): sin documento son ventas distintas.
                Row r = v.createRow(i + 1);
                r.createCell(0).setCellValue((1 + i / 2) + "-ene-2026");
                r.createCell(1).setCellValue(barras(3));
                r.createCell(2).setCellValue("PRODUCTO 3");
                r.createCell(3).setCellValue(1);
                r.createCell(4).setCellValue(10);
                r.createCell(5).setCellValue(10);
            }
            Sheet inv = wb.createSheet("Hoja2");
            Row ih = inv.createRow(0);
            String[] hh = {"Cód. Barras", "Descripción", "Existencia"};
            for (int i = 0; i < hh.length; i++) {
                ih.createCell(i).setCellValue(hh[i]);
            }
            Row x = inv.createRow(1);
            x.createCell(0).setCellValue(Double.parseDouble(barras(3)));
            x.createCell(1).setCellValue("PRODUCTO 3");
            x.createCell(2).setCellValue(12.5);
            try (OutputStream o = Files.newOutputStream(f)) {
                wb.write(o);
            }
        }
        List<LectorStreaming.Hoja> hojas = LectorStreaming.inspeccionar(f, LectorStreaming.MUESTRA, Progreso.NINGUNO);
        assertEquals(ImportType.VENTAS, ImportType.detectar(hojas.get(0).nombre(), hojas.get(0).encabezados()));
        assertEquals(ImportType.INVENTARIO, ImportType.detectar(hojas.get(1).nombre(), hojas.get(1).encabezados()));
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(20, r.insertados(), r.explicacion());
        assertEquals(10, r.lineasIguales());
        // Reimportar no duplica nada.
        var r2 = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(0, r2.insertados());
        assertEquals(20, r2.duplicados());
        LectorStreaming.Hoja h2 = hojas.get(1);
        var ri = ctx.importacion().importarPorBloques(f, h2, ImportType.INVENTARIO,
                ctx.importacion().mapeoSugerido(ImportType.INVENTARIO, h2.encabezados()), Progreso.NINGUNO);
        assertEquals(1, ri.insertados() + ri.actualizados(), ri.explicacion());
        assertEquals(13, ctx.database().queryLong("SELECT SUM(stock) FROM inventario"), "12,5 se redondea a 13");
    }

    /** Con documento, una línea idéntica es repetida salvo que se elija registrarla por separado. */
    @Test
    void lineasIdenticasConDocumentoSegunLaOpcion() throws Exception {
        String csv = "Fecha;Código;Cantidad;Precio;Boleta\n"
                + "2026-03-01;" + barras(1) + ";1;10;B-1\n"
                + "2026-03-01;" + barras(1) + ";1;10;B-1\n"
                + "2026-03-01;" + barras(2) + ";1;10;B-1\n";
        Path f = dir.resolve("boleta.csv");
        Files.writeString(f, csv);
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(2, r.insertados());
        assertEquals(1, r.duplicados());
        Path g = dir.resolve("boleta2.csv");
        Files.writeString(g, csv.replace("B-1", "B-2"));
        var s = importar(g, new ImportService.Opciones(false, true));
        assertEquals(3, s.insertados(), "dos ítems iguales en la boleta B-2");
        var s2 = importar(g, new ImportService.Opciones(false, true));
        assertEquals(0, s2.insertados(), "reimportar no duplica");
    }

    /** Códigos con ceros perdidos y en notación científica (Excel recortó los dígitos). */
    @Test
    void codigosDanadosPorExcel() throws Exception {
        String csv = "Fecha;Código;Cantidad;Precio;Boleta\n"
                + "2026-03-01;123;2;10;B-1\n"
                + "2026-03-01;7,75173E+12;1;10;B-2\n";
        Path f = dir.resolve("codigos.csv");
        Files.writeString(f, csv);
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(1, r.insertados(), "123 es el producto 000123 del catálogo");
        assertEquals(1, r.errores());
        assertTrue(r.causas().lineas(3).get(0).contains("notación científica"), r.causas().lineas(3).toString());
        assertNotNull(r.filasRechazadas());
        String rech = Files.readString(r.filasRechazadas(), StandardCharsets.UTF_8);
        assertTrue(rech.contains("7,75173E+12") && rech.contains("notación científica"), rech);
    }

    /** Relación antigua guardada en la que el código apunta a la columna del nombre: se reconoce igual. */
    @Test
    void relacionAntiguaConElNombreComoCodigo() throws Exception {
        Path f = dir.resolve("antiguo.csv");
        Files.writeString(f, "Fecha;Producto;Cantidad;Precio;Boleta\n2026-03-01;PRODUCTO 4;2;10;B-1\n");
        LectorStreaming.Hoja h = LectorStreaming.inspeccionar(f, LectorStreaming.MUESTRA, Progreso.NINGUNO).get(0);
        var r = ctx.importacion().importarPorBloques(f, h, ImportType.VENTAS,
                Map.of("fecha", 0, "producto", 1, "cantidad", 2, "precio", 3, "referencia", 4), Progreso.NINGUNO);
        assertEquals(1, r.insertados(), r.explicacion());
    }

    /** Una plantilla guardada se aplica a un archivo parecido: columnas en otro orden y una columna nueva. */
    @Test
    void plantillaSeAplicaAArchivosParecidos() {
        List<String> original = List.of("F. venta", "Cod", "Unid", "Valor");
        Map<String, Integer> mapeo = Map.of("fecha", 0, "producto", 1, "cantidad", 2, "total", 3);
        ctx.importacion().plantillas().guardar(ImportType.VENTAS, "Mi ERP", original, mapeo);
        List<String> nuevo = List.of("Observación", "Valor", "Unid", "Cod", "F. venta");
        Map<String, Integer> sug = ctx.importacion().mapeoSugerido(ImportType.VENTAS, nuevo);
        assertEquals(4, sug.get("fecha"));
        assertEquals(3, sug.get("producto"));
        assertEquals(2, sug.get("cantidad"));
        assertEquals(1, sug.get("total"));
        assertTrue(ctx.importacion().origenSugerencia(ImportType.VENTAS, nuevo).orElseThrow().contains("Mi ERP"));
        assertFalse(ctx.importacion().plantillas().masParecida(ImportType.VENTAS, List.of("Otra", "Cosa")).isPresent());
    }

    /** Las ventas importadas alimentan la predicción. */
    @Test
    void lasVentasImportadasAlimentanLaPrediccion() throws Exception {
        StringBuilder sb = new StringBuilder("Fecha;Código;Cantidad;Precio;Boleta\n");
        int n = 0;
        for (LocalDate d = LocalDate.of(2024, 1, 1); d.isBefore(LocalDate.of(2025, 12, 31)); d = d.plusDays(3)) {
            sb.append(d).append(';').append(barras(n % 3)).append(';').append(1 + n % 4).append(";10;B-").append(n++)
                    .append('\n');
        }
        Path f = dir.resolve("historia.csv");
        Files.writeString(f, sb.toString());
        var r = importar(f, ImportService.Opciones.NINGUNA);
        assertEquals(n, r.insertados());
        ctx.planificacion().generarYGuardar();
        var a = ctx.resultados().ultimo().orElseThrow();
        assertTrue(ctx.resultados().resultados(a.id()).stream()
                .filter(x -> x.codigo().equals(barras(0))).allMatch(x -> x.detalle().p30() != null));
    }
}
