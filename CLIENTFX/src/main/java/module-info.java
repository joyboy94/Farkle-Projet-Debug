module org.example.farkleclientfx {
    // JavaFX
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;     // needed for javafx.animation, Image, shapes…

    // Swagger / HTTP / JSON
    requires com.fasterxml.jackson.databind;
    requires gson;
    requires okhttp;
    requires logging.interceptor;
    requires okio;
    requires threetenbp;
    requires gson.fire;
    requires java.sql;
    requires java.annotation;
    requires swagger.annotations;

    // Open/export your Swagger models if Jackson/Gson need to reflectively access them
    opens io.swagger.client.model to gson, com.fasterxml.jackson.databind;
    exports io.swagger.client.model;

    // Your app packages
    opens org.example.farkleclientfx to javafx.fxml, com.fasterxml.jackson.databind;
    exports org.example.farkleclientfx;

    // Service package (Jackson mapping)
    opens org.example.farkleclientfx.service to com.fasterxml.jackson.databind;
    exports org.example.farkleclientfx.service;
}
