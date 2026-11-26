package com.stockwise.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Formatos de presentación (moneda, números, fechas). */
public final class Formatters {

    public static final Locale LOCALE = Locale.forLanguageTag("es-PE");
    private static final DecimalFormatSymbols SYMBOLS = new DecimalFormatSymbols(Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static volatile String currencySymbol = "S/";

    private Formatters() {
    }

    public static void setCurrencySymbol(String symbol) {
        currencySymbol = symbol == null || symbol.isBlank() ? "" : symbol.trim();
    }

    public static String currencySymbol() {
        return currencySymbol;
    }

    public static String money(double v) {
        return currencySymbol + " " + new DecimalFormat("#,##0.00", SYMBOLS).format(v);
    }

    public static String moneyShort(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) {
            return currencySymbol + " " + new DecimalFormat("#,##0.0", SYMBOLS).format(v / 1_000_000) + " M";
        }
        if (a >= 10_000) {
            return currencySymbol + " " + new DecimalFormat("#,##0.0", SYMBOLS).format(v / 1_000) + " K";
        }
        return money(v);
    }

    public static String number(double v) {
        return new DecimalFormat("#,##0.##", SYMBOLS).format(v);
    }

    public static String integer(double v) {
        return new DecimalFormat("#,##0", SYMBOLS).format(Math.round(v));
    }

    public static String percent(double ratio) {
        return new DecimalFormat("0.0", SYMBOLS).format(ratio * 100) + " %";
    }

    public static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    /** "2025-03" -> "mar 2025". */
    public static String period(String yyyyMm) {
        try {
            YearMonth ym = YearMonth.parse(yyyyMm);
            return ym.getMonth().getDisplayName(TextStyle.SHORT, LOCALE).replace(".", "") + " " + ym.getYear();
        } catch (Exception e) {
            return yyyyMm;
        }
    }
}
