package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.recommendation.PurchasePlan;
import com.stockwise.recommendation.PurchaseRecommender;
import com.stockwise.recommendation.StrategicAdvisor;
import com.stockwise.repository.RecomendacionRepository;

import java.util.List;

/** Orquesta el plan de compras y el asesor estratégico, y persiste las recomendaciones. */
public class RecommendationService {

    private final AnalysisService analysis;
    private final PresupuestoService presupuesto;
    private final RecomendacionRepository repo;
    private final InventarioService inventario;
    private final StrategicAdvisor advisor = new StrategicAdvisor();

    public RecommendationService(AnalysisService analysis, PresupuestoService presupuesto,
                                 RecomendacionRepository repo, InventarioService inventario) {
        this.analysis = analysis;
        this.presupuesto = presupuesto;
        this.repo = repo;
        this.inventario = inventario;
    }

    public PurchasePlan planDeCompras(AnalysisSnapshot snapshot, double presupuestoDisponible) {
        AppSettings s = snapshot.settings();
        return new PurchaseRecommender(new PurchaseRecommender.Params(s.horizonteMeses(), s.nivelServicio()))
                .recomendar(snapshot.productos(), presupuestoDisponible, snapshot::suministro);
    }

    public PurchasePlan planDeCompras() {
        return planDeCompras(analysis.snapshot(), presupuesto.estadoActual().disponible());
    }

    /** Ejecuta el asesor, guarda las recomendaciones pendientes y devuelve el listado completo. */
    public List<Recomendacion> generarRecomendaciones() {
        List<Recomendacion> nuevas = advisor.generar(analysis.snapshot());
        repo.replacePendientes(nuevas);
        return repo.findAll();
    }

    /** Igual que {@link #generarRecomendaciones()} con un análisis ya calculado (no recalcula pronósticos). */
    public void generarRecomendaciones(com.stockwise.analytics.AnalysisSnapshot snapshot) {
        repo.replacePendientes(advisor.generar(snapshot));
    }

    public List<Recomendacion> recomendaciones() {
        return repo.findAll();
    }

    /**
     * Aplica una recomendación. Los traslados se ejecutan sobre el inventario; el resto se marca como aplicada
     * (son decisiones comerciales que el gerente ejecuta fuera del sistema).
     */
    public void aplicar(Recomendacion r, Integer cantidadTraslado) {
        if (!RecomendacionRepository.PENDIENTE.equals(r.estado())) {
            throw new ValidationException("La recomendación ya fue procesada.");
        }
        if (r.tipo() == TipoRecomendacion.TRASLADO) {
            if (cantidadTraslado == null || cantidadTraslado <= 0) {
                throw new ValidationException("Indique una cantidad válida para el traslado.");
            }
            inventario.trasladar(r.productoId(), r.sucursalId(), r.sucursalDestinoId(), cantidadTraslado,
                    "Recomendación del asesor #" + r.id());
        }
        repo.updateEstado(r.id(), RecomendacionRepository.APLICADA);
    }

    public void descartar(Recomendacion r) {
        repo.updateEstado(r.id(), RecomendacionRepository.DESCARTADA);
    }
}
