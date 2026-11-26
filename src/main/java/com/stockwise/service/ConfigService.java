package com.stockwise.service;

import com.stockwise.repository.ConfigRepository;
import com.stockwise.util.Formatters;

import java.util.Map;
import java.util.function.Function;

/** Lectura y escritura validada de la configuración persistida en SQLite. */
public class ConfigService {

    private final ConfigRepository repo;
    private AppSettings cache;

    public ConfigService(ConfigRepository repo) {
        this.repo = repo;
    }

    public synchronized AppSettings get() {
        if (cache == null) {
            cache = load();
            Formatters.setCurrencySymbol(cache.simboloMoneda());
        }
        return cache;
    }

    private AppSettings load() {
        AppSettings d = AppSettings.defaults();
        Map<String, String> m = repo.all();
        return new AppSettings(
                str(m, "empresa.nombre", d.empresa()),
                str(m, "moneda.codigo", d.moneda()),
                str(m, "moneda.simbolo", d.simboloMoneda()),
                num(m, "presupuesto.general", d.presupuestoGeneral(), Double::parseDouble),
                num(m, "prediccion.ventana_pm", d.ventanaPromedioMovil(), Integer::parseInt),
                num(m, "prediccion.ventana_pmp", d.ventanaPonderado(), Integer::parseInt),
                num(m, "prediccion.alpha", d.alpha(), Double::parseDouble),
                num(m, "prediccion.horizonte", d.horizonteMeses(), Integer::parseInt),
                num(m, "prediccion.meses_confiables", d.mesesMinimosConfiables(), Integer::parseInt),
                num(m, "inventario.stock_minimo", d.stockMinimoDefecto(), Integer::parseInt),
                num(m, "inventario.lead_time", d.leadTimeDefectoDias(), Integer::parseInt),
                num(m, "inventario.nivel_servicio", d.nivelServicio(), Double::parseDouble),
                num(m, "inventario.meses_sobreinventario", d.mesesSobreinventario(), Double::parseDouble),
                num(m, "inventario.dias_sin_movimiento", d.diasSinMovimiento(), Integer::parseInt),
                num(m, "negocio.margen_minimo", d.margenMinimo(), Double::parseDouble),
                str(m, "ui.tema", d.tema()),
                str(m, "backup.directorio", d.directorioBackup()));
    }

    private static String str(Map<String, String> m, String k, String def) {
        String v = m.get(k);
        return v == null ? def : v;
    }

    private static <T> T num(Map<String, String> m, String k, T def, Function<String, T> parser) {
        try {
            String v = m.get(k);
            return v == null || v.isBlank() ? def : parser.apply(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Valida y guarda todos los parámetros. Lanza {@link ValidationException} si alguno es inválido. */
    public synchronized void save(AppSettings s) {
        validate(s);
        repo.set("empresa.nombre", s.empresa().trim());
        repo.set("moneda.codigo", s.moneda().trim().toUpperCase());
        repo.set("moneda.simbolo", s.simboloMoneda().trim());
        repo.set("presupuesto.general", String.valueOf(s.presupuestoGeneral()));
        repo.set("prediccion.ventana_pm", String.valueOf(s.ventanaPromedioMovil()));
        repo.set("prediccion.ventana_pmp", String.valueOf(s.ventanaPonderado()));
        repo.set("prediccion.alpha", String.valueOf(s.alpha()));
        repo.set("prediccion.horizonte", String.valueOf(s.horizonteMeses()));
        repo.set("prediccion.meses_confiables", String.valueOf(s.mesesMinimosConfiables()));
        repo.set("inventario.stock_minimo", String.valueOf(s.stockMinimoDefecto()));
        repo.set("inventario.lead_time", String.valueOf(s.leadTimeDefectoDias()));
        repo.set("inventario.nivel_servicio", String.valueOf(s.nivelServicio()));
        repo.set("inventario.meses_sobreinventario", String.valueOf(s.mesesSobreinventario()));
        repo.set("inventario.dias_sin_movimiento", String.valueOf(s.diasSinMovimiento()));
        repo.set("negocio.margen_minimo", String.valueOf(s.margenMinimo()));
        repo.set("ui.tema", s.tema());
        repo.set("backup.directorio", s.directorioBackup() == null ? "" : s.directorioBackup());
        cache = null;
        get();
    }

    /** Descarta la caché (p. ej. tras restaurar una copia de seguridad). */
    public synchronized AppSettings reload() {
        cache = null;
        return get();
    }

    public synchronized void setTema(String tema) {
        repo.set("ui.tema", tema);
        cache = null;
    }

    public static void validate(AppSettings s) {
        if (s.empresa() == null || s.empresa().isBlank()) {
            throw new ValidationException("El nombre de la empresa es obligatorio.");
        }
        if (s.simboloMoneda() == null || s.simboloMoneda().isBlank()) {
            throw new ValidationException("El símbolo de moneda es obligatorio.");
        }
        if (s.presupuestoGeneral() < 0) {
            throw new ValidationException("El presupuesto no puede ser negativo.");
        }
        if (s.ventanaPromedioMovil() < 2 || s.ventanaPromedioMovil() > 12
                || s.ventanaPonderado() < 2 || s.ventanaPonderado() > 12) {
            throw new ValidationException("Las ventanas de promedio móvil deben estar entre 2 y 12 meses.");
        }
        if (s.alpha() <= 0 || s.alpha() >= 1) {
            throw new ValidationException("El factor alfa debe estar entre 0 y 1 (exclusivo).");
        }
        if (s.horizonteMeses() < 1 || s.horizonteMeses() > 6) {
            throw new ValidationException("El horizonte de cobertura debe estar entre 1 y 6 meses.");
        }
        if (s.mesesMinimosConfiables() < 3 || s.mesesMinimosConfiables() > 36) {
            throw new ValidationException("Los meses mínimos confiables deben estar entre 3 y 36.");
        }
        if (s.stockMinimoDefecto() < 0) {
            throw new ValidationException("El stock mínimo no puede ser negativo.");
        }
        if (s.leadTimeDefectoDias() < 1 || s.leadTimeDefectoDias() > 365) {
            throw new ValidationException("El tiempo de reposición debe estar entre 1 y 365 días.");
        }
        if (s.nivelServicio() < 0.5 || s.nivelServicio() > 0.999) {
            throw new ValidationException("El nivel de servicio debe estar entre 0.50 y 0.999.");
        }
        if (s.mesesSobreinventario() < 1) {
            throw new ValidationException("El umbral de sobreinventario debe ser de al menos 1 mes.");
        }
        if (s.diasSinMovimiento() < 7) {
            throw new ValidationException("Los días sin movimiento deben ser al menos 7.");
        }
        if (s.margenMinimo() < 0 || s.margenMinimo() >= 1) {
            throw new ValidationException("El margen mínimo debe estar entre 0 y 1.");
        }
    }
}
