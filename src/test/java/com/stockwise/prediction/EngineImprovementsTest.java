package com.stockwise.prediction;

import com.stockwise.prediction.ForecastResult.MethodResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mejoras del motor: referencias, Holt, Holt-Winters, estacionalidad, atípicos, parsimonia, métricas y horizontes. */
class EngineImprovementsTest {

    private final ForecastEngine engine = new ForecastEngine(new ForecastEngine.Params(3, 3, 0.3, 6));

    private static List<String> periodos(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            p.add(YearMonth.of(2022, 1).plusMonths(i).toString());
        }
        return p;
    }

    private static double[] estacional(int n, long semilla, double ruido) {
        Random r = new Random(semilla);
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = Math.max(0, 100 + 1.5 * i + 35 * Math.sin(2 * Math.PI * i / 12.0) + r.nextGaussian() * ruido);
        }
        return s;
    }

    /** Regla del motor anterior: solo los 4 métodos clásicos, el de menor MAE walk-forward. */
    private double[] pronosticoAnterior(double[] hist, int h) {
        int inicio = engine.metodos().stream().mapToInt(ForecastMethod::minimoDatos).max().orElse(3);
        return engine.metodos().stream().min(Comparator.comparingDouble(m ->
                ForecastEvaluator.evaluar(m, hist, inicio).mae())).orElseThrow().pronosticar(hist, h);
    }

    private static double mae(double[] real, double[] pred, int desde) {
        double s = 0;
        for (int i = 0; i < pred.length; i++) {
            s += Math.abs(real[desde + i] - pred[i]);
        }
        return s / pred.length;
    }

    // ------------------------------------------------------------------ modelos

    @Test
    void modelosDeReferencia() {
        double[] h = {5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 30};
        assertArrayEquals(new double[]{30, 30}, NaiveForecast.simple().pronosticar(h, 2), 1e-9);
        // mismo mes del año anterior: índices n-12 y n-11
        assertArrayEquals(new double[]{7, 9}, NaiveForecast.estacional().pronosticar(h, 2), 1e-9);
        assertTrue(NaiveForecast.simple().referencia());
        assertEquals(0, NaiveForecast.simple().complejidad());
    }

    @Test
    void holtAmortiguadoSigueLaTendenciaSinExtrapolarIndefinidamente() {
        double[] lineal = new double[12];
        for (int i = 0; i < 12; i++) {
            lineal[i] = 20 + 4 * i;
        }
        double[] f = new HoltForecast().pronosticar(lineal, 24);
        assertTrue(f[0] > lineal[11], "Debe continuar la tendencia");
        assertTrue(f[23] < lineal[11] + 4 * 24, "La tendencia amortiguada crece menos que la recta");
        for (int i = 1; i < f.length; i++) {
            assertTrue(f[i] >= f[i - 1] - 1e-9);
        }
    }

    @Test
    void holtWintersReproduceUnaEstacionalidadPerfecta() {
        double[] s = estacional(36, 1, 0);
        double[] f = new HoltWintersForecast().pronosticar(Arrays.copyOf(s, 30), 6);
        assertTrue(mae(s, f, 30) < 3, "MAE " + mae(s, f, 30));
    }

    @Test
    void detectaEstacionalidadSoloConDosAniosDeHistoria() {
        assertTrue(ForecastEngine.autocorrelacionEstacional(estacional(36, 2, 3)) > ForecastResult.UMBRAL_ESTACIONAL);
        assertTrue(Double.isNaN(ForecastEngine.autocorrelacionEstacional(estacional(20, 2, 3))));
        double[] ruido = new double[36];
        Random r = new Random(5);
        for (int i = 0; i < 36; i++) {
            ruido[i] = 50 + r.nextGaussian() * 5;
        }
        assertTrue(ForecastEngine.autocorrelacionEstacional(ruido) < ForecastResult.UMBRAL_ESTACIONAL);
    }

    @Test
    void moderaPicosAtipicosPeroNoLaTendencia() {
        double[] s = new double[18];
        for (int i = 0; i < s.length; i++) {
            s[i] = 40 + 2 * i + (i % 3);
        }
        assertArrayEquals(s, ForecastEngine.moderarAtipicos(s), 1e-9, "Una tendencia no es atípica");
        double[] pico = s.clone();
        pico[9] = 400;
        double[] m = ForecastEngine.moderarAtipicos(pico);
        assertTrue(m[9] < 100, "El pico se modera: " + m[9]);
        assertEquals(pico[8], m[8], 1e-9);
        ForecastResult r = engine.pronosticar(periodos(18), pico);
        assertEquals(1, r.atipicos());
        assertEquals(400, r.historia()[9], 1e-9, "La historia mostrada conserva la venta real");
        assertTrue(r.mejor().pronostico()[0] < 120, "El pico no contamina el pronóstico");
    }

    // ------------------------------------------------------------------ selección

    @Test
    void parsimoniaPrefiereElModeloSimpleConErrorEquivalente() {
        MethodResult simple = new MethodResult("Simple", new double[]{1}, new double[0], 10.1, null, 6, 12.0, 0.2, 0.0,
                1, false);
        MethodResult complejo = new MethodResult("Complejo", new double[]{1}, new double[0], 10.0, null, 6, 11.0, 0.2,
                0.0, 4, false);
        assertEquals("Simple", ForecastEngine.seleccionar(List.of(complejo, simple), MethodResult::mae).metodo());
        MethodResult claramenteMejor = new MethodResult("Complejo", new double[]{1}, new double[0], 8.0, null, 6, 9.0,
                0.2, 0.0, 4, false);
        assertEquals("Complejo", ForecastEngine.seleccionar(List.of(claramenteMejor, simple), MethodResult::mae)
                .metodo());
    }

    @Test
    void demandaIntermitenteSeSeleccionaPorRmse() {
        double[] s = {0, 6, 0, 0, 7, 0, 5, 0, 0, 6, 0, 0, 7, 0, 6, 0};
        ForecastResult r = engine.pronosticar(periodos(s.length), s);
        assertEquals("RMSE", r.criterio());
        assertTrue(r.mejor().pronostico()[0] > 0, "No debe pronosticar cero para un producto que se vende");
    }

    @Test
    void laCombinacionNoMiraElFuturo() {
        double[] real = {10, 10, 10, 10, 10, 10, 10, 10};
        double[] a = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 12, 12, 12, 12};
        double[] b = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 8, 30, 30, 30};
        double[] c = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 10, 10, 10, 10};
        double[] d = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 40, 40, 40, 40};
        List<MethodResult> rs = List.of(mr("A", a), mr("B", b), mr("C", c), mr("D", d));
        MethodResult comb = ForecastEngine.combinar(rs, real, 4, false);
        assertNotNull(comb);
        // Primeros dos meses: promedio de todos (aún no hay errores previos que permitan elegir).
        assertEquals((12 + 8 + 10 + 40) / 4.0, comb.ajustes()[4], 1e-9);
        // Tercer mes: los 3 mejores según los meses 4 y 5 (C, A, B) sin usar el error del propio mes.
        assertEquals((12 + 30 + 10) / 3.0, comb.ajustes()[6], 1e-9);
        assertNull(ForecastEngine.combinar(rs.subList(0, 2), real, 4, false));
    }

    private static MethodResult mr(String n, double[] aj) {
        double s = 0;
        int k = 0;
        for (int i = 4; i < aj.length; i++) {
            s += Math.abs(aj[i] - 10);
            k++;
        }
        return new MethodResult(n, new double[]{aj[aj.length - 1], 0, 0, 0, 0, 0}, aj, s / k, null, k, null, null,
                null, 1, false);
    }

    @Test
    void elMotorMideMetricasYValorAgregado() {
        double[] s = estacional(30, 3, 6);
        ForecastResult r = engine.pronosticar(periodos(30), s);
        MethodResult m = r.mejor();
        assertNotNull(m.rmse());
        assertNotNull(m.wape());
        assertNotNull(m.sesgo());
        assertTrue(m.rmse() >= m.mae() - 1e-9, "RMSE ≥ MAE");
        assertNotNull(r.ingenuo());
        assertNotNull(r.fva());
        assertEquals(1, r.metodos().stream().mapToInt(MethodResult::puntosEvaluados).distinct().count(),
                "Todos compiten en el mismo periodo");
    }

    @Test
    void elMotorNuevoNoEsPeorQueElAnteriorEnEscenariosTipicos() {
        // Holdout de 6 meses fuera de la historia de entrenamiento; varias semillas para no depender del azar.
        double sumNuevo = 0;
        double sumAnterior = 0;
        for (long semilla = 1; semilla <= 8; semilla++) {
            double[] s = estacional(42, semilla, 8);
            double[] hist = Arrays.copyOf(s, 36);
            ForecastResult r = engine.pronosticar(periodos(36), hist);
            sumNuevo += mae(s, Arrays.copyOf(r.mejor().pronostico(), 6), 36);
            sumAnterior += mae(s, pronosticoAnterior(hist, 6), 36);
        }
        assertTrue(sumNuevo < sumAnterior * 0.8, "Con estacionalidad el motor nuevo debe mejorar claramente: "
                + sumNuevo / 8 + " vs " + sumAnterior / 8);
    }

    // ------------------------------------------------------------------ métricas y horizontes

    @Test
    void evaluadorCalculaRmseWapeYSesgo() {
        ForecastMethod constante = new ForecastMethod() {
            public String nombre() {
                return "c";
            }

            public int minimoDatos() {
                return 1;
            }

            public double[] pronosticar(double[] historia, int horizonte) {
                double[] o = new double[horizonte];
                Arrays.fill(o, 10);
                return o;
            }
        };
        ForecastEvaluator.Evaluation ev = ForecastEvaluator.evaluar(constante, new double[]{0, 8, 14}, 1);
        // errores: +2 (8) y −4 (14)
        assertEquals(3, ev.mae(), 1e-9);
        assertEquals(Math.sqrt((4 + 16) / 2.0), ev.rmse(), 1e-9);
        assertEquals(6 / 22.0, ev.wape(), 1e-9);
        assertEquals(-2 / 22.0, ev.sesgo(), 1e-9);
    }

    @Test
    void horizontesEnDiasProrrateanLosMesesYMuestranIncertidumbre() {
        List<String> fut = List.of("2025-07", "2025-08", "2025-09", "2025-10", "2025-11", "2025-12");
        double[] men = {31, 62, 30, 31, 30, 31};
        LocalDate corte = LocalDate.of(2025, 6, 30);
        assertEquals(7, HorizonForecast.acumulado(fut, men, corte, 7), 1e-9);    // 7 días de julio a 1/día
        assertEquals(31 + 2 * 10, HorizonForecast.acumulado(fut, men, corte, 41), 1e-9); // julio + 10 días de agosto
        HorizonForecast.Pronostico p = HorizonForecast.pronostico(fut, men, corte, 30, 10.0);
        double sigma = 10 * Math.sqrt(30 / 30.4);
        assertEquals(30 - HorizonForecast.Z80 * sigma, p.inferior(), 1e-9);
        assertEquals(30 + HorizonForecast.Z80 * sigma, p.superior(), 1e-9);
        assertNull(HorizonForecast.pronostico(fut, men, corte, 30, null).inferior());
        ForecastResult sin = engine.pronosticar(periodos(2), new double[]{3, 4});
        assertTrue(HorizonForecast.horizontes(sin, corte).isEmpty(), "Sin pronóstico no se inventan horizontes");
        ForecastResult con = engine.pronosticar(periodos(24), estacional(24, 4, 2));
        List<HorizonForecast.Pronostico> h = HorizonForecast.horizontes(con, LocalDate.of(2023, 12, 31));
        assertEquals(5, h.size());
        for (int i = 1; i < h.size(); i++) {
            assertTrue(h.get(i).valor() >= h.get(i - 1).valor());
        }
        assertFalse(h.get(0).inferior() > h.get(0).valor());
    }
}
