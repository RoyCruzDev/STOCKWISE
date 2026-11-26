package com.stockwise.recommendation;

/** Decisión estratégica única por producto. */
public enum DecisionEstrategica {
    RECOMPRAR("Recomprar", "La posición de inventario llegó al punto de reorden: emitir orden de compra."),
    MANTENER("Mantener", "Stock y demanda equilibrados: continuar con la política actual."),
    LIQUIDAR("Liquidar", "Stock inmovilizado o muy superior a la demanda: vender con descuento para liberar capital."),
    REDUCIR("Reducir", "Sobreinventario o demanda en descenso: reducir o suspender las próximas compras."),
    PUBLICITAR("Publicitar", "Buen margen con rotación baja o en descenso: impulsar ventas con promoción/visibilidad."),
    NEGOCIAR("Negociar", "Margen por debajo del mínimo: negociar costo con el proveedor antes de recomprar."),
    DESCONTINUAR("Descontinuar", "Sin ventas por un periodo prolongado o margen negativo: retirar del catálogo."),
    DATOS_INSUFICIENTES("Pendiente: datos insuficientes", "No hay datos suficientes para recomendar una acción.");

    private final String etiqueta;
    private final String descripcion;

    DecisionEstrategica(String etiqueta, String descripcion) {
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
