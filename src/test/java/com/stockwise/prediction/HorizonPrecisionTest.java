package com.stockwise.prediction;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Precisión por horizonte, rangos de predicción conformes, regla de confianza y estimación provisional. */
class HorizonPrecisionTest {

    private static List<String> periodos(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            p.add(YearMonth.of(2023, 1).plusMonths(i).toString());
        }
        return p;
    }

    @Test
    void metricasPorHorizonteUsanElMesPronosticadoCorrecto() {
        // Origen t pronostica los meses t, t+1, t+2 (h = 1, 2, 3).
        double[] real = {10, 20, 30};
        double[][] multipaso = {{12, 18, 33}, {19, 30, Double.NaN}, {30, Double.NaN, Double.NaN}};
        double[] h1 = ForecastEvaluator.metricasHorizonte(multipaso, real, 1);
        // errores h1: 12-10=2, 19-20=-1, 30-30=0
        assertEquals(1.0, h1[0], 1e-9);
        assertEquals(Math.sqrt(5.0 / 3), h1[1], 1e-9);
        assertEquals(3.0 / 60, h1[2], 1e-9);
        assertEquals(1.0 / 60, h1[3], 1e-9);
        assertEquals(3, h1[4]);
        double[] h2 = ForecastEvaluator.metricasHorizonte(multipaso, real, 2);
        // errores h2: 18-20=-2, 30-30=0
        assertEquals(1.0, h2[0], 1e-9);
        assertEquals(2, h2[4]);
        double[] h3 = ForecastEvaluator.metricasHorizonte(multipaso, real, 3);
        assertEquals(3.0, h3[0], 1e-9);
        assertEquals(1, h3[4]);
        double[] vacio = ForecastEvaluator.metricasHorizonte(null, real, 1);
        assertEquals(0, vacio[4]);
        assertTrue(Double.isNaN(vacio[0]));
    }

    @Test
    void erroresAcumuladosSonRealMenosPronosticoDeKMeses() {
        double[] real = {10, 20, 30};
        double[][] multipaso = {{12, 18, 33}, {19, 30, Double.NaN}, {30, Double.NaN, Double.NaN}};
        assertArrayEquals(new double[]{-2, 1, 0}, ForecastEvaluator.erroresAcumulados(multipaso, real, 1), 1e-9);
        // k=2: origen 0 → (10+20) − (12+18) = 0; origen 1 → (20+30) − (19+30) = 1
        assertArrayEquals(new double[]{0, 1}, ForecastEvaluator.erroresAcumulados(multipaso, real, 2), 1e-9);
        assertArrayEquals(new double[]{-3}, ForecastEvaluator.erroresAcumulados(multipaso, real, 3), 1e-9);
    }

    @Test
    void desviosConformesUsanElEstadisticoDeOrdenCorrecto() {
        assertNull(HorizonForecast.desvios(new double[]{5}));
        // m = 9 → k = ceil(10 × 0,8) = 8 → octavo error absoluto ordenado
        double[] e = {1, -2, 3, -4, 5, -6, 7, -8, 9};
        assertArrayEquals(new double[]{-8, 8}, HorizonForecast.desvios(e), 1e-9);
        // m = 3 → k = ceil(4 × 0,8) = 4 > m → error máximo
        assertArrayEquals(new double[]{-7, 7}, HorizonForecast.desvios(new double[]{1, -7, 3}), 1e-9);
    }

    @Test
    void rangoCreceConElHorizonteYSeInterpolaEntreMeses() {
        double[][] errores = new double[3][];
        Random rnd = new Random(1);
        for (int k = 0; k < 3; k++) {
            errores[k] = new double[12];
            for (int i = 0; i < 12; i++) {
                errores[k][i] = rnd.nextGaussian() * 10 * Math.sqrt(k + 1);
            }
        }
        ForecastResult.Horizontes hz = new ForecastResult.Horizontes(new double[3], List.of(), new double[3][5],
                errores);
        double r7 = HorizonForecast.rango(hz, 7)[1];
        double r30 = HorizonForecast.rango(hz, HorizonForecast.DIAS_MES)[1];
        double r45 = HorizonForecast.rango(hz, 45.6)[1];
        double r60 = HorizonForecast.rango(hz, 2 * HorizonForecast.DIAS_MES)[1];
        double r90 = HorizonForecast.rango(hz, 3 * HorizonForecast.DIAS_MES)[1];
        double r120 = HorizonForecast.rango(hz, 4 * HorizonForecast.DIAS_MES)[1];
        assertEquals(HorizonForecast.desvios(errores[0])[1] * Math.sqrt(7 / HorizonForecast.DIAS_MES), r7, 1e-9);
        assertEquals(HorizonForecast.desvios(errores[1])[1], r60, 1e-9);
        assertEquals((r30 + r60) / 2, r45, 1e-9);
        assertEquals(r90 * Math.sqrt(4.0 / 3), r120, 1e-9);
        assertTrue(r7 < r30);
        assertNull(HorizonForecast.rango(null, 30));
    }

    /** Fuera de muestra: con errores intercambiables el rango conforme cubre al menos ~80 % de los casos. */
    @Test
    void rangoConformeCubreAlMenosElOchentaPorCiento() {
        Random rnd = new Random(42);
        int cubiertos = 0;
        int pruebas = 4000;
        for (int i = 0; i < pruebas; i++) {
            double[] calibracion = new double[6];
            for (int j = 0; j < calibracion.length; j++) {
                calibracion[j] = rnd.nextGaussian() * 15;
            }
            double q = HorizonForecast.desvios(calibracion)[1];
            if (Math.abs(rnd.nextGaussian() * 15) <= q) {
                cubiertos++;
            }
        }
        double cobertura = cubiertos / (double) pruebas;
        assertTrue(cobertura >= 0.78, "cobertura " + cobertura);
        assertTrue(cobertura <= 0.95, "rango demasiado ancho: " + cobertura);
    }

    @Test
    void pronosticosPorHorizonteIncluyenRangoConErroresReales() {
        ForecastEngine engine = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));
        Random rnd = new Random(7);
        double[] serie = new double[24];
        for (int i = 0; i < serie.length; i++) {
            serie[i] = 100 + 2 * i + rnd.nextGaussian() * 8;
        }
        ForecastResult r = engine.pronosticar(periodos(24), serie);
        assertNotNull(r.horizontes());
        assertEquals(3, r.horizontes().metricas().length);
        assertTrue(r.horizontes().metricas()[0][4] >= 2);
        List<HorizonForecast.Pronostico> h = HorizonForecast.horizontes(r, LocalDate.of(2024, 12, 31));
        assertEquals(5, h.size());
        for (HorizonForecast.Pronostico p : h) {
            assertNotNull(p.inferior());
            assertTrue(p.inferior() <= p.valor() && p.valor() <= p.superior(), "rango " + p);
            assertTrue(p.inferior() >= 0);
        }
        assertTrue(h.get(4).valor() > h.get(2).valor());
        assertTrue(h.get(4).superior() - h.get(4).inferior() > h.get(0).superior() - h.get(0).inferior());
    }

    @Test
    void confianzaExigePrecisionDemostrada() {
        ForecastEngine engine = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));
        for (int n = 3; n <= 30; n++) {
            double[] serie = new double[n];
            for (int i = 0; i < n; i++) {
                serie[i] = 50 + (i % 3);
            }
            ForecastResult r = engine.pronosticar(periodos(n), serie);
            if (r.mejor() == null) {
                continue;
            }
            int puntos = r.mejor().puntosEvaluados();
            if (puntos < ForecastEngine.PUNTOS_CONFIANZA_MEDIA) {
                assertEquals(ForecastResult.Confianza.BAJA, r.confianza(), "n=" + n);
                assertTrue(r.advertencias().stream().anyMatch(a -> a.startsWith("Precisión no demostrada")));
            }
            if (r.confianza() == ForecastResult.Confianza.ALTA) {
                assertTrue(puntos >= ForecastEngine.PUNTOS_CONFIANZA_ALTA, "n=" + n);
            }
        }
    }

    @Test
    void estimacionProvisionalProyectaElRitmoObservado() {
        HorizonForecast.Pronostico p = HorizonForecast.provisional(60, 30, 30);
        assertEquals(60, p.valor(), 1e-9);
        assertEquals(60 - HorizonForecast.Z80 * Math.sqrt(60), p.inferior(), 1e-9);
        assertEquals(60 + HorizonForecast.Z80 * Math.sqrt(60), p.superior(), 1e-9);
        assertEquals(14, HorizonForecast.provisional(60, 30, 7).valor(), 1e-9);
        assertEquals(0, HorizonForecast.provisional(0.5, 30, 7).inferior(), 1e-9);
    }
}
