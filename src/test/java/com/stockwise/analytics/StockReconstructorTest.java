package com.stockwise.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockReconstructorTest {

    private static final LocalDate D = LocalDate.of(2025, 6, 1);

    @Test
    void reconstruyeHaciaAtrasDesdeElStockActual() {
        NavigableMap<LocalDate, double[]> f = new TreeMap<>();
        f.put(D, new double[]{0, 3});                 // venta de 3
        f.put(D.plusDays(1), new double[]{10, 0});    // compra recibida de 10
        f.put(D.plusDays(3), new double[]{0, 4});     // venta de 4
        List<StockReconstructor.DiaStock> dias = StockReconstructor.reconstruir(8, D, D.plusDays(3), f);
        assertEquals(4, dias.size());
        assertEquals(D, dias.get(0).fecha());
        // cierre día 3 = 8; día 2 = 8 + 4 = 12; día 1 = 12 (sin flujo); día 0 = 12 − 10 = 2
        assertEquals(List.of(2.0, 12.0, 12.0, 8.0), dias.stream().map(StockReconstructor.DiaStock::stock).toList());
        assertEquals(3, dias.get(0).salidas());
        assertEquals(10, dias.get(1).entradas());
    }

    @Test
    void stockNegativoSeMarcaComoInconsistente() {
        NavigableMap<LocalDate, double[]> f = new TreeMap<>();
        f.put(D.plusDays(1), new double[]{20, 0}); // entró más de lo que explica el stock actual
        List<StockReconstructor.DiaStock> dias = StockReconstructor.reconstruir(5, D, D.plusDays(2), f);
        assertEquals(-15, dias.get(0).stock());
        assertTrue(dias.get(0).negativo());
        StockReconstructor.Resumen r = StockReconstructor.resumir(dias, null, null);
        assertFalse(r.consistente());
        assertEquals(1, r.diasNegativos());
        assertEquals(D, r.primerNegativo());
    }

    @Test
    void resumenCalculaDisponibilidadEnElRango() {
        NavigableMap<LocalDate, double[]> f = new TreeMap<>();
        f.put(D.plusDays(2), new double[]{6, 0});
        // cierres: d0 = 0, d1 = 0, d2 = 6, d3 = 6
        List<StockReconstructor.DiaStock> dias = StockReconstructor.reconstruir(6, D, D.plusDays(3), f);
        StockReconstructor.Resumen r = StockReconstructor.resumir(dias, D, D.plusDays(3));
        assertEquals(4, r.dias());
        assertEquals(2, r.diasSinStock());
        assertEquals(0.5, r.disponibilidad(), 1e-9);
        assertTrue(r.consistente());
        assertNull(StockReconstructor.resumir(dias, D.plusYears(1), null).disponibilidad());
        assertEquals(D.plusDays(2), StockReconstructor.inicio(f, D.plusDays(3)));
        assertTrue(StockReconstructor.reconstruir(1, D.plusDays(1), D, f).isEmpty());
    }
}
