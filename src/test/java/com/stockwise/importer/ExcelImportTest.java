package com.stockwise.importer;

import com.stockwise.service.AppContext;
import com.stockwise.service.ValidationException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelImportTest {

    @TempDir
    Path dir;
    AppContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("test.db"));
    }

    private ImportResult importarHoja(List<SheetData> hojas, ImportType tipo) {
        SheetData hoja = hojas.stream().filter(h -> ImportType.detectar(h.nombre()) == tipo).findFirst().orElseThrow();
        ImportPreview prev = ctx.importacion().previsualizar(hoja, tipo, ColumnMapper.sugerir(tipo, hoja.encabezados()));
        return ctx.importacion().importar(prev);
    }

    @Test
    void importaElArchivoDeEjemploCompletoYDetectaDuplicados() throws Exception {
        Path file = dir.resolve("demo.xlsx");
        SampleDataGenerator.generar(file, 18, LocalDate.of(2025, 6, 30));
        List<SheetData> hojas = ExcelReader.leer(file);

        assertEquals(3, importarHoja(hojas, ImportType.SUCURSALES).insertados());
        assertEquals(16, importarHoja(hojas, ImportType.PRODUCTOS).insertados());
        assertEquals(48, importarHoja(hojas, ImportType.INVENTARIO).insertados());
        ImportResult ventas = importarHoja(hojas, ImportType.VENTAS);
        assertTrue(ventas.insertados() > 1000);
        assertEquals(ventas.insertados(), ctx.ventaRepository().count());
        assertEquals(3, importarHoja(hojas, ImportType.PRESUPUESTOS).insertados());

        // Reimportar ventas: todas duplicadas -> no hay filas válidas.
        SheetData hv = hojas.stream().filter(h -> ImportType.detectar(h.nombre()) == ImportType.VENTAS).findFirst()
                .orElseThrow();
        ImportPreview prev = ctx.importacion().previsualizar(hv, ImportType.VENTAS,
                ColumnMapper.sugerir(ImportType.VENTAS, hv.encabezados()));
        assertEquals(0, prev.importables());
        assertEquals(hv.totalFilas(), prev.count(ImportRow.Estado.DUPLICADO));
        assertThrows(ValidationException.class, () -> ctx.importacion().importar(prev));

        // Reimportar productos: se actualizan, no se duplican.
        ImportResult prods = importarHoja(hojas, ImportType.PRODUCTOS);
        assertEquals(0, prods.insertados());
        assertEquals(16, prods.actualizados());
        assertEquals(16, ctx.productos().listar().size());
    }

    @Test
    void validaErroresYDatosFaltantes() throws Exception {
        Path file = dir.resolve("errores.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Ventas");
            sh.createRow(0).createCell(0).setCellValue("Reporte de ventas"); // título antes del encabezado
            Row h = sh.createRow(2);
            String[] cols = {"Fecha de venta", "SKU", "Unidades", "Precio Unitario", "Factura"};
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i]);
            }
            Object[][] data = {
                    {"2025-01-10", "NOEXISTE", 2.0, 10.0, "F1"},
                    {"fecha mala", "NOEXISTE", 2.0, 10.0, "F2"},
                    {"10/01/2025", "NOEXISTE", null, 10.0, "F3"},
                    {"2099-01-01", "NOEXISTE", 1.0, 10.0, "F4"}};
            for (int r = 0; r < data.length; r++) {
                Row row = sh.createRow(r + 3);
                for (int c = 0; c < data[r].length; c++) {
                    Object v = data[r][c];
                    if (v instanceof String s) {
                        row.createCell(c).setCellValue(s);
                    } else if (v instanceof Double d) {
                        row.createCell(c).setCellValue(d);
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        SheetData hoja = ExcelReader.leer(file).get(0);
        assertEquals(3, hoja.filaEncabezado());
        Map<String, Integer> map = ColumnMapper.sugerir(ImportType.VENTAS, hoja.encabezados());
        assertEquals(0, map.get("fecha"));
        assertEquals(1, map.get("producto"));
        assertEquals(2, map.get("cantidad"));
        assertEquals(3, map.get("precio"));
        assertEquals(4, map.get("referencia"));

        ImportPreview prev = ctx.importacion().previsualizar(hoja, ImportType.VENTAS, map);
        assertEquals(4, prev.count(ImportRow.Estado.ERROR));
        assertTrue(prev.filas().get(0).mensajesTexto().contains("no existe"));
        assertTrue(prev.filas().get(1).mensajesTexto().contains("fecha"));
        assertTrue(prev.filas().get(2).mensajesTexto().contains("Falta el dato obligatorio"));
        assertTrue(prev.filas().get(3).mensajesTexto().contains("futura"));
        assertThrows(ValidationException.class, () -> ctx.importacion().importar(prev));
    }

    @Test
    void exigeRelacionarColumnasObligatorias() throws Exception {
        Path file = dir.resolve("plantilla.xlsx");
        TemplateGenerator.generar(file);
        List<SheetData> hojas = ExcelReader.leer(file);
        assertTrue(hojas.size() >= 5);
        SheetData prod = hojas.stream().filter(h -> h.nombre().equals("Productos")).findFirst().orElseThrow();
        Map<String, Integer> map = ColumnMapper.sugerir(ImportType.PRODUCTOS, prod.encabezados());
        assertEquals(ImportType.PRODUCTOS.campos().size(), map.size());
        assertThrows(ValidationException.class,
                () -> ctx.importacion().previsualizar(prod, ImportType.PRODUCTOS, Map.of("codigo", 0)));
    }

    @Test
    void rechazaArchivosQueNoSonExcel() throws Exception {
        Path txt = dir.resolve("datos.xlsx");
        Files.writeString(txt, "no soy un excel");
        assertThrows(ValidationException.class, () -> ExcelReader.leer(txt));
        assertThrows(ValidationException.class, () -> ExcelReader.leer(dir.resolve("a.csv")));
    }

    @Test
    void convierteValoresConFormatosVariados() {
        assertEquals(1234.5, ValueParser.numero("1,234.50"));
        assertEquals(1234.5, ValueParser.numero("1.234,50"));
        assertEquals(12.5, ValueParser.numero("S/ 12,5"));
        assertEquals(1500.0, ValueParser.numero("1,500"));
        assertEquals(LocalDate.of(2025, 3, 7), ValueParser.fecha("07/03/2025"));
        assertEquals(LocalDate.of(2025, 3, 7), ValueParser.fecha("2025-03-07 10:20"));
        assertEquals(YearMonth.of(2025, 3), ValueParser.periodo("03/2025"));
        assertEquals("1001", ValueParser.texto(1001.0));
        assertThrows(IllegalArgumentException.class, () -> ValueParser.entero(2.5));
        assertThrows(IllegalArgumentException.class, () -> ValueParser.numero("abc"));
        assertNotNull(ImportType.detectar("Ventas 2024"));
    }
}
