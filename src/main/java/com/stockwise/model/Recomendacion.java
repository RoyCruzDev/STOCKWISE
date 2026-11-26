package com.stockwise.model;

/**
 * Recomendación estratégica generada por el motor de reglas.
 * {@code beneficio} es nulo cuando no puede estimarse con los datos disponibles.
 */
public record Recomendacion(long id, String fechaGeneracion, TipoRecomendacion tipo, Prioridad prioridad,
                            Long productoId, String producto, Long sucursalId, String sucursal,
                            Long sucursalDestinoId, String sucursalDestino,
                            String problema, String accion, String justificacion, Double beneficio,
                            String estado) {

    public Recomendacion withId(long newId) {
        return new Recomendacion(newId, fechaGeneracion, tipo, prioridad, productoId, producto, sucursalId, sucursal,
                sucursalDestinoId, sucursalDestino, problema, accion, justificacion, beneficio, estado);
    }

    public Recomendacion withEstado(String nuevo) {
        return new Recomendacion(id, fechaGeneracion, tipo, prioridad, productoId, producto, sucursalId, sucursal,
                sucursalDestinoId, sucursalDestino, problema, accion, justificacion, beneficio, nuevo);
    }
}
