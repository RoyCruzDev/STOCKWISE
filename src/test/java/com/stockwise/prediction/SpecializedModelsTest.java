package com.stockwise.prediction;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecializedModelsTest {

    private final ForecastEngine engine = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));

    private static List<String> periodos(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            p.add(YearMonth.of(2023, 1).plusMonths(i).toString());
        }
        return p;
    }

    /** Serie estacional (periodo 12) con tendencia suave. */
    private static double[] estacional(int n) {
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = 100 + 2 * i + 30 * Math.sin(2 * Math.PI * i / 12.0);
        }
        return s;
    }

    // ------------------------------------------------------------------ Croston-TSB

    @Test
    void crostonTsbConParametrosFijosCalculaProbabilidadPorTamano() {
        // y = 10, 0, 10, 0 con α = β = 0.5. Inicio: z = 10, p = 0.5
        // t0 (10): p = 0.75, z = 10 · t1 (0): p = 0.375 · t2 (10): p = 0.6875 · t3 (0): p = 0.34375
        double[] f = new CrostonTsbForecast(0.5, 0.5).pronosticar(new double[]{10, 0, 10, 0}, 3);
        assertArrayEquals(new double[]{3.4375, 3.4375, 3.4375}, f, 1e-9);
    }

    @Test
    void crostonTsbDecaeCuandoElProductoDejaDeVenderse() {
        CrostonTsbForecast tsb = new CrostonTsbForecast(0.2, 0.2);
        double activo = tsb.pronosticar(new double[]{0, 8, 0, 9, 0, 8, 0, 9}, 1)[0];
        double inactivo = tsb.pronosticar(new double[]{0, 8, 0, 9, 0, 8, 0, 9, 0, 0, 0, 0, 0, 0}, 1)[0];
        assertTrue(inactivo < activo / 2, "El pronóstico debe decaer tras meses sin ventas");
        assertEquals(0, tsb.pronosticar(new double[]{0, 0, 0, 0}, 2)[0]);
    }

    @Test
    void crostonTsbOptimizaParametrosDentroDeLaRejilla() {
        double[] ab = new CrostonTsbForecast().parametros(new double[]{0, 5, 0, 0, 6, 0, 5, 0, 0, 7, 0, 5});
        assertTrue(Arrays.stream(CrostonTsbForecast.ALFAS).anyMatch(a -> a == ab[0]));
        assertTrue(Arrays.stream(CrostonTsbForecast.BETAS).anyMatch(b -> b == ab[1]));
        assertThrows(IllegalArgumentException.class, () -> new CrostonTsbForecast(0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new CrostonTsbForecast().pronosticar(new double[]{1, 2}, 1));
    }

    // ------------------------------------------------------------------ GBDT (LightGBM / CatBoost)

    @Test
    void gbdtRequiereHistorialMinimoYNoDevuelveNegativos() {
        for (GradientBoostingForecast m : List.of(new LightGbmForecast(), new CatBoostForecast())) {
            assertEquals(GradientBoostingForecast.MINIMO_DATOS, m.minimoDatos());
            assertThrows(IllegalArgumentException.class, () -> m.pronosticar(new double[10], 1));
            double[] f = m.pronosticar(estacional(30), 6);
            assertEquals(6, f.length);
            assertTrue(Arrays.stream(f).allMatch(v -> v >= 0 && Double.isFinite(v)));
            assertArrayEquals(new double[3], m.pronosticar(new double[24], 3), 0, "Serie sin ventas -> 0");
        }
    }

    @Test
    void gbdtEsDeterministaYAprendeLaEstacionalidad() {
        double[] s = estacional(36);
        double[] hist = Arrays.copyOf(s, 30);
        for (GradientBoostingForecast m : List.of(new LightGbmForecast(), new CatBoostForecast())) {
            double[] a = m.pronosticar(hist, 6);
            assertArrayEquals(a, m.pronosticar(hist, 6), 0, "Mismo entrenamiento -> mismo resultado");
            double mae = 0;
            double maeMedia = 0;
            double media = Arrays.stream(hist).average().orElse(0);
            for (int i = 0; i < 6; i++) {
                mae += Math.abs(a[i] - s[30 + i]);
                maeMedia += Math.abs(media - s[30 + i]);
            }
            assertTrue(mae < maeMedia, m.nombre() + " debe mejorar al promedio histórico (MAE " + mae / 6 + " vs "
                    + maeMedia / 6 + ")");
        }
    }

    @Test
    void estadisticosOrdenadosSoloUsanFilasAnteriores() {
        double[] y = {10, 20, 30, 40};
        int[] cat = {0, 0, 1, 0};
        double[] ts = CatBoostForecast.estadisticosOrdenados(y, cat, 5, 1);
        assertEquals(5, ts[0], 1e-9);              // sin historia: el valor previo
        assertEquals((10 + 5) / 2.0, ts[1], 1e-9);   // solo la fila 0
        assertEquals(5, ts[2], 1e-9);              // primera de su categoría
        assertEquals((10 + 20 + 5) / 3.0, ts[3], 1e-9); // nunca incluye su propio objetivo
    }

    @Test
    void histogramaAsignaBinsCoherentesConLosUmbrales() {
        double[][] x = {{1}, {2}, {2}, {5}, {9}};
        double[][] b = GradientBoostingForecast.bordes(x, 32);
        assertArrayEquals(new double[]{1, 2, 5, 9}, b[0], 0);
        assertEquals(1, GradientBoostingForecast.bin(b[0], 2));
        assertEquals(2, GradientBoostingForecast.bin(b[0], 3)); // 3 <= 5
        assertEquals(3, GradientBoostingForecast.bin(b[0], 100));
        assertEquals(4, GradientBoostingForecast.bordes(new double[][]{{1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}}, 4)[0].length);
    }

    // ------------------------------------------------------------------ selección en el motor

    @Test
    void elMotorClasificaYEvaluaLosModelosEspecializadosSegunElPatron() {
        ForecastResult suave = engine.pronosticar(periodos(30), estacional(30));
        assertEquals(DemandPattern.SUAVE, suave.patron());
        assertTrue(suave.metodos().stream().anyMatch(m -> m.metodo().startsWith("LightGBM")));
        assertFalse(suave.metodos().stream().anyMatch(m -> m.metodo().startsWith("Croston")));
        // Todos los métodos se comparan en el mismo periodo de validación.
        assertEquals(1, suave.metodos().stream().mapToInt(ForecastResult.MethodResult::puntosEvaluados).distinct().count());

        double[] inter = {0, 6, 0, 0, 7, 0, 5, 0, 0, 6, 0, 0, 7, 0, 6, 0};
        ForecastResult r = engine.pronosticar(periodos(inter.length), inter);
        assertEquals(DemandPattern.INTERMITENTE, r.patron());
        assertTrue(r.metodos().stream().anyMatch(m -> m.metodo().equals("Croston-TSB")));
    }

    @Test
    void sinHistorialSuficienteElModeloEspecializadoNoSeAplicaYSeAdvierte() {
        ForecastResult r = engine.pronosticar(periodos(14), estacional(14));
        assertEquals(DemandPattern.SUAVE, r.patron());
        assertTrue(r.metodos().stream().noneMatch(m -> m.metodo().startsWith("LightGBM")));
        assertTrue(r.advertencias().stream().anyMatch(a -> a.contains("LightGBM") && a.contains("no se aplicó")));
    }

    @Test
    void especializadosPorPatron() {
        assertEquals(List.of("LightGBM (GBDT por hojas)"), nombres(DemandPattern.SUAVE));
        assertEquals(List.of("CatBoost (árboles simétricos)"), nombres(DemandPattern.ERRATICA));
        assertEquals(List.of("Croston-TSB"), nombres(DemandPattern.INTERMITENTE));
        assertEquals(List.of("Croston-TSB", "CatBoost (árboles simétricos)"), nombres(DemandPattern.IRREGULAR));
        assertTrue(ForecastEngine.especializados(DemandPattern.SIN_CLASIFICAR).isEmpty());
    }

    private static List<String> nombres(DemandPattern p) {
        return ForecastEngine.especializados(p).stream().map(ForecastMethod::nombre).toList();
    }
}
