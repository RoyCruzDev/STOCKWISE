package com.stockwise.controller;

import com.stockwise.service.AppContext;

/** Base de los controladores de módulo: acceso a servicios y a la ventana principal. */
public abstract class BaseController {

    protected final Shell shell;
    protected final AppContext app;

    protected BaseController(Shell shell) {
        this.shell = shell;
        this.app = shell.app();
    }
}
