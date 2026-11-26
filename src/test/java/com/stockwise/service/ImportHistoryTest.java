package com.stockwise.service;

import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportPreview;
import com.stockwise.importer.ImportRow;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SheetData;
import com.stockwise.model.Producto;
import com.stockwise.repository.ImportacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importación acumulativa por meses, detección de periodos repetidos, historial y eliminación segura. */
class ImportHistoryTest {

    @TempDir
    Path dir;
    AppContext ctx;
    long suc;

    @BeforeEach
    void setUp() {
        ctx = new AppContext(dir.resolve("hist.db"));
        suc = ctx.sucursales().listar().get(0).id();
        for (String c : List.of("A1", "B1")) {
            ctx.productos().guardar(new Producto(0, c, "Prod " + c, null, null, null, null, 10, 6, 0, 0, null, true),
                    "Cat", "Prov");
        }
    }

    private static final String ENC = "Fecha;Código de producto;Cantidad;Precio unitario;Referencia\n";

    /** Escribe un CSV de ventas; cada fila "AAAA-MM-DD;COD;cant;ref". */
    private Path csv(String nombre, String... filas) throws Exception {
        StringBuilder sb = new StringBuilder(ENC);
        for (String f : filas) {
            String[] p = f.split(";");
            sb.append(p[0]).append(';').append(p[1]).append(';').append(p[2]).append(";10;").append(p[3]).append('\n');
        }
        Path f = dir.resolve(nombre);
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return f;
    }

    private ImportPreview preview(Path f) {
        SheetData h = ExcelReader.leer(f).get(0);
        return ctx.importacion().previsualizar(h, ImportType.VENTAS,
                ctx.importacion().mapeoSugerido(ImportType.VENTAS, h.encabezados()));
    }

    private int importar(Path f) {
        SheetData h = ExcelReader.leer(f).get(0);
        return ctx.importacion().importar(preview(f), f.getFileName().toString(), h.encabezados()).insertados();
    }

    private long importacionId(String archivo) {
        return ctx.historialImportaciones().listar().stream().filter(i -> i.archivo().equals(archivo)).findFirst()
                .orElseThrow().id();
    }

    @Test
    void acumulaMesesYDetectaRepetidosPorClaveDeNegocio() throws Exception {
        assertEquals(2, importar(csv("ventas-enero.csv", "2025-01-10;A1;3;F1", "2025-01-20;B1;2;F2")));
        assertEquals(2, importar(csv("ventas-febrero.csv", "2025-02-05;A1;4;F3", "2025-02-15;B1;1;F4")));
        assertEquals(4, ctx.ventaRepository().count());

        // El mismo archivo con otro nombre: todo repetido, nada nuevo para importar.
        Path copia = csv("copia-enero.csv", "2025-01-10;A1;3;F1", "2025-01-20;B1;2;F2");
        ImportPreview p = preview(copia);
        assertEquals(2, p.count(ImportRow.Estado.DUPLICADO));
        assertThrows(ValidationException.class, () -> ctx.importacion().importar(p, "copia-enero.csv", null));

        // Un archivo que se superpone: se advierte y solo entra lo nuevo.
        Path trimestre = csv("ventas-ene-mar.csv", "2025-01-10;A1;3;F1", "2025-02-05;A1;4;F3", "2025-03-01;A1;6;F5");
        ImportPreview pt = preview(trimestre);
        assertEquals(2, ctx.importacion().importacionesSolapadas(pt).size());
        assertEquals(LocalDate.of(2025, 1, 10), ImportServiceAccess.periodo(pt)[0]);
        assertEquals(1, importar(trimestre));
        assertEquals(5, ctx.ventaRepository().count());

        var historial = ctx.historialImportaciones().listar();
        assertEquals(3, historial.size());
        var t = historial.get(0);
        assertEquals("ventas-ene-mar.csv", t.archivo());
        assertEquals(1, t.insertados());
        assertEquals(2, t.duplicados());
        assertEquals(LocalDate.of(2025, 3, 1), t.periodoHasta());
        // El historial completo alimenta la serie de predicción.
        assertEquals(3, ctx.ventaRepository().mensual(null, null).size());
    }

    @Test
    void eliminarUnaImportacionConservaRegistrosCompartidosYManuales() throws Exception {
        importar(csv("enero.csv", "2025-01-10;A1;3;F1", "2025-01-20;B1;2;F2"));
        importar(csv("ene-feb.csv", "2025-01-10;A1;3;F1", "2025-02-05;A1;4;F3"));
        long manual = ctx.ventas().registrar(LocalDate.of(2025, 1, 25), ctx.productos().listar().get(0).id(), suc, 9,
                10, "MANUAL", false);
        // Un archivo posterior contiene también la venta registrada a mano.
        Path conManual = dir.resolve("con-manual.csv");
        Files.writeString(conManual, ENC + "2025-01-25;A1;9;10;MANUAL\n2025-01-26;B1;1;10;F9\n");
        importar(conManual);

        long enero = importacionId("enero.csv");
        ImportHistoryService.Impacto imp = ctx.historialImportaciones().impacto(enero);
        assertEquals(1, imp.ventasEliminar(), "Solo F2 pertenece exclusivamente a enero");
        assertEquals(1, imp.ventasConservar(), "F1 también está en ene-feb");
        ctx.historialImportaciones().eliminar(enero);
        assertEquals(5 - 1, ctx.ventaRepository().count());
        assertEquals(ImportacionRepository.ELIMINADA, ctx.historialImportaciones().listar().stream()
                .filter(i -> i.id() == enero).findFirst().orElseThrow().estado());
        assertThrows(ValidationException.class, () -> ctx.historialImportaciones().eliminar(enero));

        // F1 pasó a pertenecer a ene-feb: al eliminarla se borran F1 y F3.
        assertEquals(2, ctx.historialImportaciones().impacto(importacionId("ene-feb.csv")).ventasEliminar());
        ctx.historialImportaciones().eliminar(importacionId("ene-feb.csv"));
        assertEquals(2, ctx.ventaRepository().count());

        // La venta manual nunca se borra al eliminar un archivo que también la contenía.
        ImportHistoryService.Impacto im = ctx.historialImportaciones().impacto(importacionId("con-manual.csv"));
        assertEquals(1, im.registrosAjenos());
        ctx.historialImportaciones().eliminar(importacionId("con-manual.csv"));
        assertTrue(ctx.ventaRepository().findById(manual).isPresent());
        assertEquals(1, ctx.ventaRepository().count());
    }

    @Test
    void lasDevolucionesSeAcumulanYSeEliminanConSuImportacion() throws Exception {
        Path f = dir.resolve("ventas-y-nc.csv");
        Files.writeString(f, ENC + "2025-01-10;A1;10;10;F1\n2025-01-12;A1;-3;10;NC1\n");
        importar(f);
        assertEquals(7.0, ctx.ventaRepository().mensualPorProducto().get(0).unidades(), 1e-9);
        var imp = ctx.historialImportaciones().impacto(importacionId("ventas-y-nc.csv"));
        assertEquals(1, imp.devolucionesEliminar());
        ctx.historialImportaciones().eliminar(imp.importacion().id());
        assertTrue(ctx.ventaRepository().mensualPorProducto().isEmpty());
    }

    @Test
    void detectaCambiosDeEstructuraYReutilizaMapeos() throws Exception {
        importar(csv("enero.csv", "2025-01-10;A1;3;F1"));
        SheetData h = ExcelReader.leer(dir.resolve("enero.csv")).get(0);
        assertTrue(ctx.importacion().cambioEstructura(ImportType.VENTAS, h.encabezados()).isEmpty());
        List<String> nuevos = List.of("Fecha", "Código de producto", "Cantidad", "Total", "Vendedor");
        String msg = ctx.importacion().cambioEstructura(ImportType.VENTAS, nuevos).orElseThrow();
        assertTrue(msg.contains("vendedor"), msg);
        assertTrue(msg.contains("precio unitario"), msg);
        // Los datos maestros no se borran al eliminar su importación.
        Path prod = dir.resolve("productos.csv");
        Files.writeString(prod, "Código;Nombre;Precio de venta;Costo\nC9;Prod C9;5;3\n");
        SheetData hp = ExcelReader.leer(prod).get(0);
        ctx.importacion().importar(ctx.importacion().previsualizar(hp, ImportType.PRODUCTOS,
                ctx.importacion().mapeoSugerido(ImportType.PRODUCTOS, hp.encabezados())), "productos.csv",
                hp.encabezados());
        var imp = ctx.historialImportaciones().impacto(importacionId("productos.csv"));
        assertEquals(0, imp.totalEliminar());
        assertTrue(imp.mensajes().get(0).contains("se conservan"));
        ctx.historialImportaciones().eliminar(imp.importacion().id());
        assertFalse(ctx.productos().listar().stream().noneMatch(p -> p.codigo().equals("C9")));
    }

    /** Acceso al cálculo del periodo (método estático del servicio de importación). */
    static final class ImportServiceAccess {
        static LocalDate[] periodo(ImportPreview p) {
            return com.stockwise.importer.ImportService.periodo(p);
        }
    }
}
