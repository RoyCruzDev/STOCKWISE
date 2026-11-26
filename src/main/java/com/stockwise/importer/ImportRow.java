package com.stockwise.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fila validada de una importación. */
public final class ImportRow {

    public enum Estado {
        NUEVO("Nuevo"), ACTUALIZA("Actualiza"), DUPLICADO("Duplicado (se omite)"), OMITIDA("Omitida"), ERROR("Error");

        private final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private final int numeroFila;
    private final Map<String, Object> valores = new LinkedHashMap<>();
    private final List<String> mensajes = new ArrayList<>();
    private Estado estado = Estado.NUEVO;

    public ImportRow(int numeroFila) {
        this.numeroFila = numeroFila;
    }

    public int numeroFila() {
        return numeroFila;
    }

    public Map<String, Object> valores() {
        return valores;
    }

    public Object get(String clave) {
        return valores.get(clave);
    }

    public void put(String clave, Object valor) {
        valores.put(clave, valor);
    }

    public Estado estado() {
        return estado;
    }

    public void setEstado(Estado e) {
        // Un error o una fila omitida no cambian de estado.
        if (estado != Estado.ERROR && estado != Estado.OMITIDA) {
            estado = e;
        }
    }

    public void error(String mensaje) {
        estado = Estado.ERROR;
        mensajes.add(mensaje);
    }

    /** Marca la fila como omitida a propósito (totales del reporte, devoluciones): no es un error. */
    public void omitir(String mensaje) {
        if (estado != Estado.ERROR) {
            estado = Estado.OMITIDA;
        }
        mensajes.add(mensaje);
    }

    public void nota(String mensaje) {
        mensajes.add(mensaje);
    }

    public List<String> mensajes() {
        return mensajes;
    }

    public String mensajesTexto() {
        return String.join("; ", mensajes);
    }

    public boolean importable() {
        return estado == Estado.NUEVO || estado == Estado.ACTUALIZA;
    }
}
