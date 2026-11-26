package com.stockwise.service;

import com.stockwise.model.Producto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proyectos independientes: creación, aislamiento de datos, renombrado y eliminación con confirmación. */
class ProjectCatalogTest {

    @TempDir
    Path dir;

    private static long producto(AppContext ctx, String codigo) {
        return ctx.productos().guardar(new Producto(0, codigo, "Prod " + codigo, null, null, null, null, 10, 6, 0, 0,
                null, true), "Cat", "Prov");
    }

    @Test
    void losDatosDeCadaProyectoNuncaSeMezclan() {
        ProjectCatalog c = new ProjectCatalog(dir);
        assertTrue(c.listar().isEmpty());
        var ropa = c.crear("Ropa");
        var alimentos = c.crear("  Alimentos  ");
        assertEquals("Alimentos", alimentos.nombre());
        AppContext a = c.abrir(ropa.id());
        AppContext b = c.abrir(alimentos.id());
        long p = producto(a, "CAMISA-1");
        a.ventas().registrar(LocalDate.of(2025, 1, 10), p, a.sucursales().listar().get(0).id(), 3, 10, "F1", false);
        producto(b, "ARROZ-1");
        assertEquals(1, a.productos().listar().size());
        assertEquals("CAMISA-1", a.productos().listar().get(0).codigo());
        assertEquals(1, b.productos().listar().size());
        assertEquals("ARROZ-1", b.productos().listar().get(0).codigo());
        assertEquals(1, a.ventaRepository().count());
        assertEquals(0, b.ventaRepository().count());
        assertFalse(c.rutaBase(ropa).equals(c.rutaBase(alimentos)));
        // Reabrir conserva los datos.
        assertEquals(1, c.abrir(ropa.id()).ventaRepository().count());
    }

    @Test
    void validaNombresYRenombra() {
        ProjectCatalog c = new ProjectCatalog(dir);
        var p = c.crear("Electrónica");
        assertThrows(ValidationException.class, () -> c.crear("electrónica"));
        assertThrows(ValidationException.class, () -> c.crear("   "));
        c.crear("Hogar");
        assertThrows(ValidationException.class, () -> c.renombrar(p.id(), "HOGAR"));
        c.renombrar(p.id(), "Tecnología");
        assertEquals("Tecnología", c.buscar(p.id()).orElseThrow().nombre());
    }

    @Test
    void registraLaBaseDeVersionesAnterioresComoPrimerProyecto() throws Exception {
        AppContext viejo = new AppContext(dir.resolve("stockwise.db"));
        producto(viejo, "OLD-1");
        ProjectCatalog c = new ProjectCatalog(dir);
        assertEquals(1, c.listar().size());
        assertEquals(ProjectCatalog.NOMBRE_INICIAL, c.listar().get(0).nombre());
        assertEquals(1, c.abrir(c.listar().get(0).id()).productos().listar().size());
        // No se vuelve a registrar al reiniciar.
        assertEquals(1, new ProjectCatalog(dir).listar().size());
    }

    @Test
    void eliminarExigeConfirmacionYGuardaCopia() throws Exception {
        ProjectCatalog c = new ProjectCatalog(dir);
        var ropa = c.crear("Ropa");
        var otro = c.crear("Alimentos");
        producto(c.abrir(ropa.id()), "X1");
        producto(c.abrir(otro.id()), "Y1");
        Path base = c.rutaBase(ropa);
        assertThrows(ValidationException.class, () -> c.eliminar(ropa.id(), "Rop"));
        assertTrue(Files.exists(base));
        Path copia = c.eliminar(ropa.id(), "ropa");
        assertNotNull(copia);
        assertTrue(Files.exists(copia), "Copia de seguridad antes de eliminar");
        assertFalse(Files.exists(base));
        assertFalse(Files.exists(base.getParent()), "Se borra la carpeta propia del proyecto");
        assertTrue(Files.isDirectory(dir.resolve("proyectos")), "Nunca se borra la carpeta común");
        assertEquals(1, c.listar().size());
        assertEquals(1, c.abrir(otro.id()).productos().listar().size(), "Los demás proyectos no cambian");
    }
}
