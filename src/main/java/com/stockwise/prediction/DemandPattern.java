package com.stockwise.prediction;

/**
 * Patrón de demanda según la clasificación de Syntetos-Boylan (ADI / CV²).
 * <ul>
 *   <li>ADI: intervalo medio entre periodos con demanda (periodos / periodos con demanda).</li>
 *   <li>CV²: cuadrado del coeficiente de variación de los tamaños de demanda no nulos.</li>
 * </ul>
 * Umbrales clásicos: ADI = 1.32 y CV² = 0.49.
 */
public enum DemandPattern {
    SUAVE("Suave", "Demanda regular y estable: modelos de tendencia/estacionalidad (GBDT tipo LightGBM)."),
    ERRATICA("Errática", "Demanda frecuente con tamaños muy variables: modelo robusto a ruido (GBDT tipo CatBoost)."),
    INTERMITENTE("Intermitente", "Muchos periodos sin ventas y tamaños estables: Croston-TSB."),
    IRREGULAR("Irregular (lumpy)", "Periodos sin ventas y tamaños muy variables: Croston-TSB / GBDT tipo CatBoost."),
    SIN_CLASIFICAR("Sin clasificar", "Historial insuficiente para clasificar el patrón de demanda.");

    public static final double ADI_CORTE = 1.32;
    public static final double CV2_CORTE = 0.49;

    private final String etiqueta;
    private final String descripcion;

    DemandPattern(String etiqueta, String descripcion) {
        this.etiqueta = etiqueta;
        this.descripcion = descripcion;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public String descripcion() {
        return descripcion;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
