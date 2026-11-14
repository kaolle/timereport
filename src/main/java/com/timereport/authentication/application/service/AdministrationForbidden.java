package com.timereport.authentication.application.service;

public class AdministrationForbidden extends RuntimeException {
    public AdministrationForbidden() {
        super("Du behöver administratörsbehörighet för att använda den här funktionen.");
    }
}
