package com.stockwise.importer;

import com.stockwise.model.Compra;
import com.stockwise.model.Producto;
import com.stockwise.model.Proveedor;
import com.stockwise.service.AppContext;
import com.stockwise.service.ValidationException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importación avanzada de reportes ERP: CSV, XLS, totales, devoluciones, órdenes de compra, proveedores, perfiles. */
class ErpImportTest {

    @TempDir
    Path dir;
    AppContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("erp.db"));
        ctx.productos().guardar(new Producto(0, "A1", "Producto A1", null, null, null, null, 10.5, 6, 0, 0, null, true),
                "General", "Prov Y");
    }

    private Producto producto(String codigo) {
        return ctx.productos().listar().stream().filter(p -> p.codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private ImportPreview previsualizar(SheetData h, ImportType t) {
        return ctx.importacion().previsualizar(h, t, ctx.importacion().mapeoSugerido(t, h.encabezados()));
    }

    @Test
    void csvDeErpConTitulosWindows1252TotalesYDevoluciones() throws Exception {
        String csv = """
                REPORTE DE VENTAS - ERP XYZ
                Generado: 01/07/2025
                Fecha Doc.;Cód. Artículo;Descripción;Almacén;Cant.;P. Unit;Importe Total;Nro Doc
                05/06/2025;A1;"Producto; especial";PRINCIPAL;3;10,50;31,50;F001-1
                06/06/25;A1;Producto;PRINCIPAL;2;10,50;21,00;F001-2
                07/06/2025;A1;Producto;PRINCIPAL;-1;10,50;-10,50;NC001-1
                ;;TOTAL GENERAL;;4;;42,00;
                """;
        Path f = dir.resolve("ventas_erp_junio.csv");
        Files.write(f, csv.getBytes(Charset.forName("windows-1252")));

        List<SheetData> hojas = ExcelReader.leer(f);
        assertEquals(1, hojas.size());
        SheetData h = hojas.get(0);
        assertEquals(ImportType.VENTAS, ImportType.detectar(h.nombre()));
        assertEquals(3, h.filaEncabezado());
        assertTrue(h.encabezados().contains("Descripción"), "Debe decodificar Windows-1252");
        assertEquals("Producto; especial", h.filas().get(0).get(2));

        ImportPreview p = previsualizar(h, ImportType.VENTAS);
        // 2 ventas + 1 devolución (se registra y se descuenta de la demanda); la fila de totales se omite.
        assertEquals(3, p.count(ImportRow.Estado.NUEVO));
        assertEquals(1, p.count(ImportRow.Estado.OMITIDA));
        assertEquals(0, p.count(ImportRow.Estado.ERROR));
        assertTrue(p.resumen().contains("omitidas"));
        assertEquals(LocalDate.of(2025, 6, 6), p.filas().get(1).get("fecha"));
        assertEquals(Boolean.TRUE, p.filas().get(2).get("_devolucion"));
        assertEquals(3, ctx.importacion().importar(p, f.getFileName().toString(), h.encabezados()).insertados());
        assertEquals(2, ctx.ventaRepository().count());
        // Demanda neta de junio: 3 + 2 vendidas − 1 devuelta.
        assertEquals(4.0, ctx.ventaRepository().mensualPorProducto().get(0).unidades(), 1e-9);
    }

    @Test
    void libroXlsAntiguoSeLeeConPoi() throws Exception {
        Path f = dir.resolve("catalogo.xls");
        try (HSSFWorkbook wb = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(f)) {
            Sheet sh = wb.createSheet("Productos");
            Row h = sh.createRow(0);
            String[] enc = {"Cod. Articulo", "Descripcion Articulo", "Precio Lista", "Costo Promedio"};
            for (int i = 0; i < enc.length; i++) {
                h.createCell(i).setCellValue(enc[i]);
            }
            Row r = sh.createRow(1);
            r.createCell(0).setCellValue("B2");
            r.createCell(1).setCellValue("Producto B2");
            r.createCell(2).setCellValue(20);
            r.createCell(3).setCellValue(12);
            wb.write(out);
        }
        SheetData h = ExcelReader.leer(f).get(0);
        ImportPreview p = previsualizar(h, ImportType.PRODUCTOS);
        assertEquals(1, ctx.importacion().importar(p).insertados());
        assertEquals(12, producto("B2").costo());
    }

    @Test
    void ordenesDeCompraYProveedoresDesdeErp() throws Exception {
        Path prov = dir.resolve("proveedores.csv");
        Files.writeString(prov, "Proveedor,Tiempo de entrega (días),Ciclo de compra (días)\nProv Y,12,15\nNuevo SAC,20,\n",
                StandardCharsets.UTF_8);
        ImportPreview pp = previsualizar(ExcelReader.leer(prov).get(0), ImportType.PROVEEDORES);
        assertEquals(1, pp.count(ImportRow.Estado.ACTUALIZA));
        assertEquals(1, pp.count(ImportRow.Estado.NUEVO));
        ctx.importacion().importar(pp);
        Proveedor y = ctx.compras().proveedores().stream().filter(p -> p.nombre().equals("Prov Y")).findFirst()
                .orElseThrow();
        assertEquals(12, y.leadTimeDias());
        assertEquals(15, y.cicloCompraDias());

        String csv = "﻿Fecha de pedido;Código de producto;Cantidad;Costo unitario;Estado;Fecha esperada de llegada;"
                + "Fecha de recepción;Nro. de orden / documento\n"
                + "01/05/2025;A1;40;6;Recibida;;10/05/2025;OC-1\n"
                + "20/06/2025;A1;25;6;En tránsito;05/07/2025;;OC-2\n"
                + "21/06/2025;A1;10;6;Anulada;;;OC-3\n"
                + "22/06/2025;A1;10;6;xyz;;;OC-4\n"
                + "23/06/2025;A1;5;6;Recibida;;20/06/2025;OC-5\n"
                + "01/05/2025;A1;40;6;Recibida;;10/05/2025;OC-1\n";
        Path oc = dir.resolve("ordenes_compra.csv");
        Files.writeString(oc, csv, StandardCharsets.UTF_8);
        SheetData h = ExcelReader.leer(oc).get(0);
        assertEquals(ImportType.COMPRAS, ImportType.detectar(h.nombre()));
        ImportPreview p = previsualizar(h, ImportType.COMPRAS);
        assertEquals(3, p.count(ImportRow.Estado.NUEVO));
        assertEquals(2, p.count(ImportRow.Estado.ERROR), "Estado desconocido y recepción anterior al pedido");
        assertEquals(1, p.count(ImportRow.Estado.DUPLICADO));
        assertEquals(3, ctx.importacion().importar(p).insertados());

        // La orden pendiente es stock en tránsito; las importadas no tocan el inventario.
        long a1 = producto("A1").id();
        assertEquals(25, ctx.compras().enTransito().get(a1));
        assertEquals(0, ctx.inventario().stock(a1, ctx.sucursales().listar().get(0).id()));
        assertEquals(25, ctx.analysis().snapshot().suministro(a1).enTransito());
        List<Compra> todas = ctx.compras().recientes(10);
        assertTrue(todas.stream().allMatch(c -> Compra.ORIGEN_IMPORTACION.equals(c.origen())));
        assertEquals(1, todas.stream().filter(c -> Compra.CANCELADA.equals(c.estado())).count());
        assertEquals(LocalDate.of(2025, 5, 10), todas.stream().filter(c -> "OC-1".equals(c.documento())).findFirst()
                .orElseThrow().fechaRecepcion());

        // Reimportar: todo duplicado.
        ImportPreview again = previsualizar(h, ImportType.COMPRAS);
        assertEquals(0, again.importables());
    }

    @Test
    void recuerdaLaRelacionDeColumnasPorFormatoDeReporte() {
        List<String> enc = List.of("F", "Articulo X", "Q", "Importe");
        assertTrue(ctx.importacion().perfilGuardado(ImportType.VENTAS, enc).isEmpty());
        Map<String, Integer> manual = Map.of("fecha", 0, "producto", 1, "cantidad", 2, "total", 3);
        ctx.importacion().guardarPerfil(ImportType.VENTAS, enc, manual);
        assertEquals(manual, ctx.importacion().perfilGuardado(ImportType.VENTAS, enc).orElseThrow());
        assertEquals(manual, ctx.importacion().mapeoSugerido(ImportType.VENTAS, enc));
        assertTrue(ctx.importacion().perfilGuardado(ImportType.VENTAS, List.of("F", "Articulo X", "Q")).isEmpty());
        assertTrue(ctx.importacion().perfilGuardado(ImportType.COMPRAS, enc).isEmpty());
    }

    @Test
    void conversionesYDeteccionesDeReportesErp() {
        assertEquals(-12.5, ValueParser.numero("(12,50)"));
        assertEquals(LocalDate.of(2025, 3, 7), ValueParser.fecha("07/03/25"));
        assertEquals("PENDIENTE", ValueParser.estadoCompra("En tránsito"));
        assertEquals("RECIBIDA", ValueParser.estadoCompra("CERRADA"));
        assertEquals("CANCELADA", ValueParser.estadoCompra("anulado"));
        assertNull(ValueParser.estadoCompra(" "));
        assertThrows(IllegalArgumentException.class, () -> ValueParser.estadoCompra("quizás"));
        assertEquals(ImportType.PRESUPUESTOS, ImportType.detectar("Presupuestos de compra"));
        assertEquals(ImportType.PROVEEDORES, ImportType.detectar("maestro_proveedores"));
        assertEquals(ImportType.COMPRAS, ImportType.detectar("Órdenes de compra"));
        assertEquals(ImportType.VENTAS, ImportType.detectar("reporte ventas 2025"));
        assertEquals(',', CsvReader.detectarSeparador("a,b,c\n1,2,3\n4,5,6"));
        assertEquals('\t', CsvReader.detectarSeparador("a\tb\tc\n1,5\t2\t3"));
        assertTrue(ImportService.esFilaDeTotales(List.of("", "Total general", 10.0)));
        assertTrue(!ImportService.esFilaDeTotales(List.of("A1", "Total Gym Pro", 10.0)));
    }

    @Test
    void rechazaFormatosNoAdmitidos() throws Exception {
        Path pdf = dir.resolve("reporte.pdf");
        Files.writeString(pdf, "%PDF");
        assertThrows(ValidationException.class, () -> ExcelReader.leer(pdf));
        Path vacio = dir.resolve("vacio.csv");
        Files.writeString(vacio, "solo una columna\n1\n2\n");
        assertThrows(ValidationException.class, () -> ExcelReader.leer(vacio));
    }
}
