package com.stockwise.ui;

import com.stockwise.controller.MainController;
import com.stockwise.controller.View;
import com.stockwise.importer.ColumnMapper;
import com.stockwise.importer.ExcelReader;
import com.stockwise.importer.ImportType;
import com.stockwise.importer.SampleDataGenerator;
import com.stockwise.importer.SheetData;
import com.stockwise.service.AppContext;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Herramienta de desarrollo (no es una prueba automática): carga datos de demostración, abre cada módulo y
 * guarda una captura PNG. Uso: java ... com.stockwise.ui.ScreenshotTool carpetaSalida [tema] [vista...]
 */
public final class ScreenshotTool {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "target/screenshots");
        String tema = args.length > 1 ? args[1] : "claro";
        List<View> vistas = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            vistas.add(View.valueOf(args[i]));
        }
        if (vistas.isEmpty()) {
            vistas.addAll(List.of(View.values()));
        }
        Files.createDirectories(out);
        Path data = Files.createTempDirectory("stockwise-shot");
        AppContext ctx = new AppContext(data.resolve("demo.db"));
        Path xlsx = data.resolve("demo.xlsx");
        SampleDataGenerator.generar(xlsx, 24, LocalDate.now().withDayOfMonth(1).minusDays(1));
        List<SheetData> hojas = ExcelReader.leer(xlsx);
        for (ImportType tipo : ImportType.values()) {
            SheetData h = hojas.stream().filter(s -> ImportType.detectar(s.nombre()) == tipo).findFirst().orElseThrow();
            ctx.importacion().importar(ctx.importacion().previsualizar(h, tipo,
                    ColumnMapper.sugerir(tipo, h.encabezados())));
        }
        ctx.recomendaciones().generarRecomendaciones();
        ctx.config().setTema(tema);

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        MainController[] main = new MainController[1];
        Scene[] scene = new Scene[1];
        runFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(ScreenshotTool.class.getResource("/views/main.fxml"));
                main[0] = new MainController(ctx);
                loader.setControllerFactory(t -> main[0]);
                Parent root = loader.load();
                Stage stage = new Stage();
                scene[0] = new Scene(root, 1440, 900);
                stage.setScene(scene[0]);
                main[0].applyTheme(tema);
                stage.show();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        for (View v : vistas) {
            runFx(() -> main[0].navigate(v));
            Thread.sleep(3500);
            Path file = out.resolve(v.name().toLowerCase() + "-" + tema + ".png");
            runFx(() -> save(scene[0].snapshot(null), file));
            System.out.println("Captura: " + file.toAbsolutePath());
        }
        Platform.exit();
        System.exit(0);
    }

    private static void runFx(Runnable r) throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                l.countDown();
            }
        });
        if (!l.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Tiempo de espera agotado en el hilo JavaFX");
        }
    }

    private static void save(WritableImage img, Path file) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(bi, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
