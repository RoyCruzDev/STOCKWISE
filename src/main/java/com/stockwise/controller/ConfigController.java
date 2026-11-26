package com.stockwise.controller;

import com.stockwise.service.AppSettings;
import com.stockwise.util.Dialogs;
import com.stockwise.util.Forms;
import com.stockwise.util.Icons;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

public class ConfigController extends BaseController {

    @FXML
    private Button saveButton;
    @FXML
    private TextField companyField;
    @FXML
    private TextField currencyCodeField;
    @FXML
    private TextField currencySymbolField;
    @FXML
    private TextField budgetField;
    @FXML
    private TextField marginField;
    @FXML
    private ComboBox<String> themeCombo;
    @FXML
    private TextField maWindowField;
    @FXML
    private TextField wmaWindowField;
    @FXML
    private TextField alphaField;
    @FXML
    private TextField minMonthsField;
    @FXML
    private TextField horizonField;
    @FXML
    private TextField minStockField;
    @FXML
    private TextField leadTimeField;
    @FXML
    private TextField serviceLevelField;
    @FXML
    private TextField overstockField;
    @FXML
    private TextField noMovementField;
    @FXML
    private TextField backupDirField;
    @FXML
    private Label dbPathLabel;
    @FXML
    private Label backupStatus;

    public ConfigController(Shell shell) {
        super(shell);
    }

    @FXML
    private void initialize() {
        saveButton.setGraphic(Icons.SAVE.create(14));
        themeCombo.getItems().setAll("claro", "oscuro");
        themeCombo.setConverter(Forms.converter(t -> t == null ? "" : "oscuro".equals(t) ? "Oscuro" : "Claro"));
        for (TextField tf : new TextField[]{budgetField, marginField, alphaField, serviceLevelField, overstockField}) {
            Forms.makeDecimal(tf);
        }
        for (TextField tf : new TextField[]{maWindowField, wmaWindowField, minMonthsField, horizonField, minStockField,
                leadTimeField, noMovementField}) {
            Forms.makeInteger(tf);
        }
        dbPathLabel.setText(app.database().getFile().toString());
        cargar(app.config().get());
    }

    private void cargar(AppSettings s) {
        companyField.setText(s.empresa());
        currencyCodeField.setText(s.moneda());
        currencySymbolField.setText(s.simboloMoneda());
        budgetField.setText(Forms.format(s.presupuestoGeneral()));
        marginField.setText(Forms.format(Math.round(s.margenMinimo() * 1000) / 10.0));
        themeCombo.setValue(s.tema());
        maWindowField.setText(String.valueOf(s.ventanaPromedioMovil()));
        wmaWindowField.setText(String.valueOf(s.ventanaPonderado()));
        alphaField.setText(Forms.format(s.alpha()));
        minMonthsField.setText(String.valueOf(s.mesesMinimosConfiables()));
        horizonField.setText(String.valueOf(s.horizonteMeses()));
        minStockField.setText(String.valueOf(s.stockMinimoDefecto()));
        leadTimeField.setText(String.valueOf(s.leadTimeDefectoDias()));
        serviceLevelField.setText(Forms.format(Math.round(s.nivelServicio() * 1000) / 10.0));
        overstockField.setText(Forms.format(s.mesesSobreinventario()));
        noMovementField.setText(String.valueOf(s.diasSinMovimiento()));
        backupDirField.setText(s.directorioBackup() == null || s.directorioBackup().isBlank()
                ? app.backup().defaultDirectory().toString() : s.directorioBackup());
    }

    private AppSettings leer() {
        return new AppSettings(companyField.getText().trim(), currencyCodeField.getText().trim(),
                currencySymbolField.getText().trim(), Forms.decimal(budgetField, "Presupuesto general", false, 0),
                Forms.integer(maWindowField, "Ventana promedio móvil", true, 3),
                Forms.integer(wmaWindowField, "Ventana promedio ponderado", true, 3),
                Forms.decimal(alphaField, "Alfa", true, 0.3),
                Forms.integer(horizonField, "Comprar para cubrir", true, 1),
                Forms.integer(minMonthsField, "Meses mínimos confiables", true, 6),
                Forms.integer(minStockField, "Stock mínimo por defecto", true, 0),
                Forms.integer(leadTimeField, "Tiempo de reposición", true, 15),
                Forms.decimal(serviceLevelField, "Nivel de servicio", true, 95) / 100.0,
                Forms.decimal(overstockField, "Exceso de stock", true, 4),
                Forms.integer(noMovementField, "Días sin movimiento", true, 90),
                Forms.decimal(marginField, "Margen mínimo", true, 15) / 100.0,
                themeCombo.getValue() == null ? "claro" : themeCombo.getValue(),
                backupDirField.getText().trim());
    }

    @FXML
    private void save() {
        try {
            AppSettings s = leer();
            app.config().save(s);
            shell.applyTheme(s.tema());
            shell.refreshHeader();
            shell.status("Configuración guardada");
            Dialogs.info("Configuración guardada", "Los nuevos parámetros se aplicarán en los próximos análisis.");
        } catch (RuntimeException e) {
            Dialogs.error(e);
        }
    }

    @FXML
    private void restoreDefaults() {
        if (Dialogs.confirm("¿Restablecer valores por defecto?", "Se cargarán los valores recomendados en el "
                + "formulario (excepto empresa y moneda). Pulse 'Guardar cambios' para aplicarlos.")) {
            AppSettings actual = leer();
            AppSettings d = AppSettings.defaults();
            cargar(new AppSettings(actual.empresa(), actual.moneda(), actual.simboloMoneda(), actual.presupuestoGeneral(),
                    d.ventanaPromedioMovil(), d.ventanaPonderado(), d.alpha(), d.horizonteMeses(),
                    d.mesesMinimosConfiables(), d.stockMinimoDefecto(), d.leadTimeDefectoDias(), d.nivelServicio(),
                    d.mesesSobreinventario(), d.diasSinMovimiento(), d.margenMinimo(), actual.tema(),
                    actual.directorioBackup()));
        }
    }

    @FXML
    private void chooseBackupDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Carpeta para copias de seguridad");
        File f = dc.showDialog(shell.window());
        if (f != null) {
            backupDirField.setText(f.getAbsolutePath());
        }
    }

    @FXML
    private void backup() {
        String dir = backupDirField.getText().trim();
        shell.run("Creando copia de seguridad…", () -> app.backup().crearCopia(dir.isEmpty() ? null : Path.of(dir)),
                p -> {
                    backupStatus.setText("Última copia: " + p);
                    Dialogs.info("Copia de seguridad creada", "Archivo:\n" + p);
                });
    }

    @FXML
    private void restore() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Seleccionar copia de seguridad");
        String dir = backupDirField.getText().trim();
        if (!dir.isEmpty() && new File(dir).isDirectory()) {
            fc.setInitialDirectory(new File(dir));
        }
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Base de datos STOCKWISE (*.db)", "*.db"));
        File f = fc.showOpenDialog(shell.window());
        if (f == null || !Dialogs.confirm("¿Restaurar la base de datos?", "Se reemplazarán TODOS los datos actuales "
                + "por los de la copia:\n" + f.getAbsolutePath() + "\n\nSe creará antes una copia automática del "
                + "estado actual.")) {
            return;
        }
        shell.run("Restaurando…", () -> {
            app.backup().restaurar(f.toPath());
            return f;
        }, r -> {
            AppSettings s = app.config().reload();
            cargar(s);
            shell.applyTheme(s.tema());
            shell.refreshHeader();
            Dialogs.info("Restauración completada", "Los datos se restauraron desde:\n" + r.getAbsolutePath());
            shell.navigate(View.DASHBOARD);
        });
    }
}
