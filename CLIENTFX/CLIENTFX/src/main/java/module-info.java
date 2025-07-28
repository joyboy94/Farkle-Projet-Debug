module org.example.farkleclientfx {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.fasterxml.jackson.databind;    // ➡️ Jackson JSON
    requires gson;
    requires okhttp;
    requires logging.interceptor;
    requires okio;
    requires threetenbp;
    requires gson.fire;
    requires java.sql;
    requires java.annotation;
    requires swagger.annotations;

    opens io.swagger.client.model to gson, com.fasterxml.jackson.databind;
    exports io.swagger.client.model;

    opens org.example.farkleclientfx to javafx.fxml, com.fasterxml.jackson.databind;
    exports org.example.farkleclientfx;

    // Ouvre aussi le package service si tu fais du mapping JSON dans ce package
    opens org.example.farkleclientfx.service to com.fasterxml.jackson.databind;
    exports org.example.farkleclientfx.service;
}
