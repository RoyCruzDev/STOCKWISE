package com.stockwise.prediction;

import java.util.Arrays;

/** Clasificación ADI/CV² del patrón de demanda de una serie mensual. */
public final class DemandClassifier {

    /** Mínimo de periodos para clasificar con algún sentido estadístico. */
    public static final int MINIMO_PERIODOS = 6;

    /**
     * @param adi                 intervalo medio entre demandas (NaN si no hay datos suficientes)
     * @param cv2                 CV² de los tamaños no nulos (NaN si no hay datos suficientes)
     * @param periodos            longitud de la serie analizada
     * @param periodosConDemanda  periodos con ventas mayores que cero
     */
    public record Clasificacion(DemandPattern patron, double adi, double cv2, int periodos, int periodosConDemanda) {

        public static Clasificacion sinClasificar(int periodos, int conDemanda) {
            return new Clasificacion(DemandPattern.SIN_CLASIFICAR, Double.NaN, Double.NaN, periodos, conDemanda);
        }

        public String resumen() {
            if (patron == DemandPattern.SIN_CLASIFICAR) {
                return "Sin clasificar: " + periodos + " periodo(s), " + periodosConDemanda + " con ventas (se requieren "
                        + MINIMO_PERIODOS + " periodos y al menos 2 con ventas).";
            }
            return String.format("%s (ADI %.2f, CV² %.2f; %d de %d periodos con ventas)", patron.etiqueta(), adi, cv2,
                    periodosConDemanda, periodos);
        }
    }

    private DemandClassifier() {
    }

    public static Clasificacion clasificar(double[] serie) {
        int n = serie == null ? 0 : serie.length;
        double[] positivos = n == 0 ? new double[0] : Arrays.stream(serie).filter(v -> v > 0).toArray();
        int k = positivos.length;
        if (n < MINIMO_PERIODOS || k < 2) {
            return Clasificacion.sinClasificar(n, k);
        }
        double adi = n / (double) k;
        double media = Arrays.stream(positivos).average().orElse(0);
        double var = Arrays.stream(positivos).map(v -> (v - media) * (v - media)).sum() / k;
        double cv2 = media == 0 ? 0 : var / (media * media);
        DemandPattern p;
        if (adi < DemandPattern.ADI_CORTE) {
            p = cv2 < DemandPattern.CV2_CORTE ? DemandPattern.SUAVE : DemandPattern.ERRATICA;
        } else {
            p = cv2 < DemandPattern.CV2_CORTE ? DemandPattern.INTERMITENTE : DemandPattern.IRREGULAR;
        }
        return new Clasificacion(p, adi, cv2, n, k);
    }
}
