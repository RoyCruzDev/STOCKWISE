package com.stockwise.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Configura el registro de errores en archivos rotativos dentro de data/logs. */
public final class AppLogger {

    private AppLogger() {
    }

    public static void init(Path dataDir) {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s - %5$s%6$s%n");
        try {
            Path logs = dataDir.resolve("logs");
            Files.createDirectories(logs);
            LogManager.getLogManager().reset();
            Logger root = Logger.getLogger("");
            FileHandler fh = new FileHandler(logs.resolve("stockwise-%g.log").toString(), 2_000_000, 5, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);
            root.addHandler(fh);
            java.util.logging.ConsoleHandler ch = new java.util.logging.ConsoleHandler();
            ch.setLevel(Level.WARNING);
            root.addHandler(ch);
            root.setLevel(Level.INFO);
            // Silencia el ruido de librerías de terceros.
            Logger.getLogger("org.apache").setLevel(Level.WARNING);
        } catch (IOException e) {
            System.err.println("No se pudo inicializar el registro de errores: " + e.getMessage());
        }
    }
}
