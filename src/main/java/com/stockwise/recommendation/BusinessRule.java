package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoRecomendacion;

import java.time.LocalDateTime;
import java.util.List;

/** Regla de negocio del asesor estratégico. Cada regla analiza la fotografía y emite recomendaciones. */
public interface BusinessRule {

    String nombre();

    List<Recomendacion> evaluar(AnalysisSnapshot snapshot);

    /** Construye una recomendación pendiente para un producto (y opcionalmente sucursales). */
    static Recomendacion crear(TipoRecomendacion tipo, Prioridad prioridad, ProductMetrics m, Sucursal origen,
                               Sucursal destino, String problema, String accion, String justificacion,
                               Double beneficio) {
        return new Recomendacion(0, LocalDateTime.now().withNano(0).toString().replace('T', ' '), tipo, prioridad,
                m == null ? null : m.productoId(), m == null ? null : m.producto().toString(),
                origen == null ? null : origen.id(), origen == null ? null : origen.codigo(),
                destino == null ? null : destino.id(), destino == null ? null : destino.codigo(),
                problema, accion, justificacion,
                beneficio == null || beneficio <= 0 ? null : Math.round(beneficio * 100) / 100.0, "PENDIENTE");
    }
}
