package com.stockwise.importer;

import com.stockwise.repository.ImportacionRepository;
import com.stockwise.service.AppContext;
import com.stockwise.service.Progreso;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduce el error real de importación: reporte de ventas de un ERP cuyo código (SKU) no coincide con el catálogo,
 * que identifica el producto por nombre y trae un almacén no registrado. Antes: todas las filas con error y ninguna
 * explicación. Ahora: el producto se reconoce por su nombre, los motivos se agrupan y se puede crear lo que falta.
 */
class ImportReporteErpTest {

    static final String[] H = {"N", "Fecha Hora", "Comprobante", "Código de barra", "Cliente", "Producto", "Almacén",
            "SKU", "Categoría", "Precio Promedio", "Costo", "Cantidad", "Total"};
    static final int PRODUCTOS = 30;
    static final int FILAS = 600;

    @TempDir
    Path dir;
    AppContext ctx;
    Path ventas;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AppContext(dir.resolve("erp.db"));
        Path cat = dir.resolve("catalogo.xlsx");
        ventas = dir.resolve("reporte_ventas.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("productos");
            String[] hc = {"Código", "Nombre", "Categoría", "Precio venta", "Costo"};
            Row h = s.createRow(0);
            for (int i = 0; i < hc.length; i++) {
                h.createCell(i).setCellValue(hc[i]);
            }
            for (int i = 0; i < PRODUCTOS; i++) {
                Row r = s.createRow(i + 1);
                r.createCell(0).setCellValue(String.valueOf(7751731000000L + i));
                r.createCell(1).setCellValue("PRODUCTO " + i);
                r.createCell(2).setCellValue("CAT");
                r.createCell(3).setCellValue(10);
                r.createCell(4).setCellValue(6);
            }
            try (OutputStream o = Files.newOutputStream(cat)) {
                wb.write(o);
            }
        }
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("ventas_productos");
            Row h = s.createRow(0);
            for (int i = 0; i < H.length; i++) {
                h.createCell(i).setCellValue(H[i]);
            }
            for (int i = 0; i < FILAS; i++) {
                // Los últimos 5 productos del reporte no están en el catálogo (productos realmente nuevos).
                int p = i % (PRODUCTOS + 5);
                Row r = s.createRow(i + 1);
                r.createCell(0).setCellValue(i + 1);
                r.createCell(1).setCellValue(String.format("2025-%02d-%02d 10:%02d:00", 1 + i % 12, 1 + i % 28, i % 60));
                r.createCell(2).setCellValue("B001-" + i);
                r.createCell(3).setCellValue(String.valueOf(7751731000000L + p));
                r.createCell(4).setCellValue("CLIENTES VARIOS");
                r.createCell(5).setCellValue("PRODUCTO " + p);
                r.createCell(6).setCellValue("ALMACEN PRINCIPAL");
                r.createCell(7).setCellValue("SKU-" + p);
                r.createCell(8).setCellValue("BEBIDAS");
                r.createCell(9).setCellValue(10);
                r.createCell(10).setCellValue(6);
                r.createCell(11).setCellValue(2);
                r.createCell(12).setCellValue(20);
            }
            try (OutputStream o = Files.newOutputStream(ventas)) {
                wb.write(o);
            }
        }
        SheetData hc = ExcelReader.leer(cat).get(0);
        ctx.importacion().importar(ctx.importacion().previsualizar(hc, ImportType.PRODUCTOS,
                ColumnMapper.sugerir(ImportType.PRODUCTOS, hc.encabezados())));
    }

    private LectorStreaming.Hoja hoja() {
        return LectorStreaming.inspeccionar(ventas, LectorStreaming.MUESTRA, Progreso.NINGUNO).get(0);
    }

    @Test
    void elCodigoSeRelacionaConSkuYElNombreConProducto() {
        LectorStreaming.Hoja h = hoja();
        Map<String, Integer> m = ColumnMapper.sugerir(ImportType.VENTAS, h.encabezados());
        assertEquals("SKU", h.encabezados().get(m.get("producto")));
        assertEquals("Producto", h.encabezados().get(m.get("nombre_producto")));
        assertEquals("Almacén", h.encabezados().get(m.get("sucursal")));
        assertEquals("Costo", h.encabezados().get(m.get("costo")));
    }

    @Test
    void sinCrearFaltantesExplicaLosMotivosYQuedaComoFallida() {
        LectorStreaming.Hoja h = hoja();
        var r = ctx.importacion().importarPorBloques(ventas, h, ImportType.VENTAS,
                ColumnMapper.sugerir(ImportType.VENTAS, h.encabezados()), Progreso.NINGUNO);
        assertEquals(0, r.insertados());
        assertEquals(FILAS, r.errores());
        assertTrue(r.fallida());
        // Los productos del catálogo se reconocieron por su nombre: el único motivo común es la sucursal.
        assertEquals(FILAS, r.causas().filas("sucursal"));
        assertEquals(FILAS / (PRODUCTOS + 5) * 5 + Math.min(5, Math.max(0, FILAS % (PRODUCTOS + 5) - PRODUCTOS)),
                r.causas().filas("producto"));
        assertEquals(5, r.causas().distintos("producto"));
        String texto = r.explicacion();
        assertTrue(texto.contains("NO SE GUARDÓ NINGUNA VENTA"), texto);
        assertTrue(texto.contains("ALMACEN PRINCIPAL"), texto);
        assertTrue(texto.contains("Qué puede hacer"), texto);
        var imp = ctx.historialImportaciones().listar().get(0);
        assertEquals(ImportacionRepository.FALLIDA, imp.estado());
        assertNotNull(imp.detalle());
        assertTrue(imp.detalle().contains("sucursal"));
    }

    @Test
    void conCrearFaltantesGuardaTodoSinDuplicarProductosDelCatalogo() {
        LectorStreaming.Hoja h = hoja();
        var r = ctx.importacion().importarPorBloques(ventas, h, ImportType.VENTAS,
                ColumnMapper.sugerir(ImportType.VENTAS, h.encabezados()), true, Progreso.NINGUNO);
        assertEquals(0, r.errores());
        assertEquals(FILAS, r.insertados() + r.duplicados());
        assertEquals(5, r.productosCreados());
        assertEquals(1, r.sucursalesCreadas());
        assertEquals(PRODUCTOS + 5, ctx.productos().listar().size());
        var nuevo = ctx.productos().listar().stream().filter(p -> p.codigo().equals("SKU-" + PRODUCTOS)).findFirst()
                .orElseThrow();
        assertEquals("PRODUCTO " + PRODUCTOS, nuevo.nombre());
        assertEquals(6, nuevo.costo(), 1e-9);
        assertEquals(10, nuevo.precioVenta(), 1e-9);
        // Reimportar: todo se detecta como repetido y no se crea nada más.
        var r2 = ctx.importacion().importarPorBloques(ventas, h, ImportType.VENTAS,
                ColumnMapper.sugerir(ImportType.VENTAS, h.encabezados()), true, Progreso.NINGUNO);
        assertEquals(0, r2.insertados());
        assertEquals(0, r2.productosCreados() + r2.sucursalesCreadas());
    }

    @Test
    void vistaPreviaConCrearNoEscribeYLaImportacionNormalCrea() {
        SheetData s = ExcelReader.leer(ventas).get(0);
        Map<String, Integer> m = ColumnMapper.sugerir(ImportType.VENTAS, s.encabezados());
        ImportPreview sin = ctx.importacion().previsualizar(s, ImportType.VENTAS, m);
        assertEquals(0, sin.importables());
        ImportPreview con = ctx.importacion().previsualizar(s, ImportType.VENTAS, m, true);
        assertEquals(FILAS, con.importables() + con.count(ImportRow.Estado.DUPLICADO));
        assertEquals(PRODUCTOS, ctx.productos().listar().size(), "la vista previa no debe crear nada");
        assertTrue(con.filas().stream().anyMatch(f -> f.mensajesTexto().contains("se creará")));
        var res = ctx.importacion().importar(con, "reporte_ventas.xlsx", s.encabezados());
        assertTrue(res.insertados() > 0);
        assertEquals(PRODUCTOS + 5, ctx.productos().listar().size());
        assertFalse(ctx.sucursales().listar().stream().noneMatch(x -> x.nombre().equals("ALMACEN PRINCIPAL")));
    }

    @Test
    void resumenAgrupaCausasYSugiereSoluciones() {
        ImportRow a = new ImportRow(2);
        a.error("El producto 'X1' no existe (importe primero los productos)");
        ImportRow b = new ImportRow(3);
        b.error("El producto 'X2' no existe (importe primero los productos)");
        ImportRow c = new ImportRow(4);
        c.error("La fecha 2099-01-01 es futura");
        ImportRow ok = new ImportRow(5);
        ResumenErrores r = ResumenErrores.de(List.of(a, b, c, ok));
        List<String> l = r.lineas(5);
        assertEquals(2, l.size());
        assertTrue(l.get(0).startsWith("2 fila(s): El producto no existe"), l.get(0));
        assertTrue(l.get(0).contains("2 distinto(s), ej.: X1, X2"), l.get(0));
        assertTrue(l.get(1).contains("(primera: fila 4)"), l.get(1));
        assertEquals(2, r.sugerencias().size());
    }
}
