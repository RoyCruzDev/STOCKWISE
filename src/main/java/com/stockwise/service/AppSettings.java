package com.stockwise.service;

/**
 * Parámetros configurables del sistema.
 *
 * @param ventanaPromedioMovil        meses usados por el promedio móvil simple
 * @param ventanaPonderado            meses usados por el promedio móvil ponderado
 * @param alpha                       factor de suavizamiento exponencial (0..1)
 * @param horizonteMeses              meses a cubrir con una compra (además del tiempo de reposición)
 * @param mesesMinimosConfiables      meses de historial a partir de los cuales una predicción se considera confiable
 * @param stockMinimoDefecto          stock mínimo aplicado a productos sin stock mínimo propio
 * @param leadTimeDefectoDias         tiempo de reposición por defecto
 * @param nivelServicio               probabilidad objetivo de no quedarse sin stock (stock de seguridad)
 * @param mesesSobreinventario        cobertura (meses) a partir de la cual se considera sobreinventario
 * @param diasSinMovimiento           días sin ventas para considerar un producto sin rotación
 * @param margenMinimo                margen mínimo aceptable sobre el precio (0..1)
 */
public record AppSettings(String empresa, String moneda, String simboloMoneda, double presupuestoGeneral,
                          int ventanaPromedioMovil, int ventanaPonderado, double alpha, int horizonteMeses,
                          int mesesMinimosConfiables, int stockMinimoDefecto, int leadTimeDefectoDias,
                          double nivelServicio, double mesesSobreinventario, int diasSinMovimiento,
                          double margenMinimo, String tema, String directorioBackup) {

    public static AppSettings defaults() {
        return new AppSettings("Mi Empresa S.A.C.", "PEN", "S/", 0, 3, 3, 0.3, 1, 6, 5, 15, 0.95, 4, 90, 0.15,
                "claro", "");
    }
}
