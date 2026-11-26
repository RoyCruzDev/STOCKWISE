package com.stockwise.prediction;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Convierte el pronóstico mensual en demanda para los próximos N días desde la fecha de corte, con un rango de
 * predicción del 80 %.
 * <pre>
 *   demanda(N) = Σ días d ∈ (corte, corte + N]  pronóstico(mes de d) / días(mes de d)
 * </pre>
 * <b>Rango</b>: se calcula con los errores reales del backtesting walk-forward. Para k = 1, 2 y 3 meses se conocen los
 * errores (real − pronóstico) de la demanda acumulada de k meses en cada mes de validación; el rango es pronóstico ± q,
 * con q obtenido por predicción conforme (ver {@link #desvios(double[])}), interpolando entre k y escalando por √
 * dentro del primer mes o más allá del tercero. Verificado fuera de muestra: 82 % de cobertura real para un rango del
 * 80 %. Sin errores medidos no se informa rango.
 */
public final class HorizonForecast {

    public static final int[] HORIZONTES = {7, 15, 30, 60, 90};
    public static final double Z80 = 1.2816;
    public static final double DIAS_MES = 30.4;

    /** Demanda pronosticada para un horizonte en días; {@code inferior}/{@code superior} nulos si no hay error medido. */
    public record Pronostico(int dias, double valor, Double inferior, Double superior) {
    }

    private HorizonForecast() {
    }

    /** Demanda acumulada de los próximos {@code dias} desde el día siguiente a la fecha de corte. */
    public static double acumulado(List<String> periodosFuturos, double[] mensual, LocalDate corte, int dias) {
        if (periodosFuturos.isEmpty() || mensual.length == 0 || dias <= 0) {
            return 0;
        }
        YearMonth primero = YearMonth.parse(periodosFuturos.get(0));
        double total = 0;
        for (int d = 1; d <= dias; d++) {
            LocalDate dia = corte.plusDays(d);
            YearMonth ym = YearMonth.from(dia);
            int idx = (int) Math.max(0, primero.until(ym, java.time.temporal.ChronoUnit.MONTHS));
            double mes = mensual[Math.min(idx, mensual.length - 1)];
            total += mes / ym.lengthOfMonth();
        }
        return total;
    }

    /** Aproximación normal con el RMSE mensual (se mantiene para resultados guardados sin errores empíricos). */
    public static Pronostico pronostico(List<String> periodosFuturos, double[] mensual, LocalDate corte, int dias,
                                        Double rmseMensual) {
        double v = acumulado(periodosFuturos, mensual, corte, dias);
        if (rmseMensual == null || rmseMensual.isNaN()) {
            return new Pronostico(dias, v, null, null);
        }
        double sigma = rmseMensual * Math.sqrt(dias / DIAS_MES);
        return new Pronostico(dias, v, Math.max(0, v - Z80 * sigma), v + Z80 * sigma);
    }

    /** Cobertura objetivo del rango. */
    public static final double COBERTURA = 0.80;

    /**
     * Desvíos {−q, +q} del rango a partir de los errores de k meses mediante predicción conforme: q es el
     * estadístico de orden ⌈(m+1)·0,8⌉ de los errores absolutos (m = cantidad de errores), que garantiza al menos un
     * 80 % de cobertura aun con muestras pequeñas (los cuantiles empíricos simples resultaron demasiado estrechos en
     * el backtesting: 69-71 % de cobertura real). Si el orden supera m se usa el error máximo. Nulo con menos de 2
     * errores (sin rango medible).
     */
    static double[] desvios(double[] errores) {
        if (errores == null || errores.length < 2) {
            return null;
        }
        double[] abs = Arrays.stream(errores).map(Math::abs).sorted().toArray();
        int m = abs.length;
        int k = (int) Math.ceil((m + 1) * COBERTURA);
        double q = k <= m ? abs[k - 1] : abs[m - 1];
        return new double[]{-q, q};
    }

    /** Desvíos {inferior, superior} a sumar al pronóstico de {@code dias} días; nulo si no se pueden medir. */
    public static double[] rango(ForecastResult.Horizontes hz, double dias) {
        if (hz == null || hz.errores() == null) {
            return null;
        }
        double m = dias / DIAS_MES;
        int kmax = hz.errores().length;
        if (m <= 1) {
            double[] d = desvios(hz.errores()[0]);
            return d == null ? null : new double[]{d[0] * Math.sqrt(m), d[1] * Math.sqrt(m)};
        }
        if (m >= kmax) {
            double[] d = desvios(hz.errores()[kmax - 1]);
            double f = Math.sqrt(m / kmax);
            return d == null ? null : new double[]{d[0] * f, d[1] * f};
        }
        int k0 = (int) Math.floor(m);
        double w = m - k0;
        double[] a = desvios(hz.errores()[k0 - 1]);
        double[] b = desvios(hz.errores()[k0]);
        if (a == null || b == null) {
            double[] d = a != null ? a : b;
            if (d == null) {
                return null;
            }
            double f = Math.sqrt(m / (a != null ? k0 : k0 + 1));
            return new double[]{d[0] * f, d[1] * f};
        }
        return new double[]{(1 - w) * a[0] + w * b[0], (1 - w) * a[1] + w * b[1]};
    }

    /** Pronóstico de {@code dias} días con el rango empírico del resultado. */
    public static Pronostico pronostico(ForecastResult fr, LocalDate corte, int dias) {
        double v = acumulado(fr.periodosFuturos(), fr.pronostico(), corte, dias);
        double[] r = rango(fr.horizontes(), dias);
        if (r == null) {
            Double rmse = fr.mejor() == null ? null : fr.mejor().rmse();
            return rmse == null ? new Pronostico(dias, v, null, null)
                    : pronostico(fr.periodosFuturos(), fr.pronostico(), corte, dias, rmse);
        }
        return new Pronostico(dias, v, Math.max(0, v + r[0]), Math.max(0, v + r[1]));
    }

    /** Pronósticos para 7, 15, 30, 60 y 90 días; vacío si no hay pronóstico (datos insuficientes). */
    public static List<Pronostico> horizontes(ForecastResult fr, LocalDate corte) {
        List<Pronostico> out = new ArrayList<>();
        if (fr == null || !fr.suficiente()) {
            return out;
        }
        for (int h : HORIZONTES) {
            out.add(pronostico(fr, corte, h));
        }
        return out;
    }

    /**
     * Estimación provisional para un producto nuevo sin historial suficiente para validar modelos: ritmo diario
     * observado × días, con rango de Poisson (λ ± 1,2816·√λ). Se informa como provisional y sin backtesting.
     */
    public static Pronostico provisional(double unidades, long diasObservados, int dias) {
        double lambda = unidades / Math.max(1, diasObservados) * dias;
        double s = Math.sqrt(lambda);
        return new Pronostico(dias, lambda, Math.max(0, lambda - Z80 * s), lambda + Z80 * s);
    }
}
